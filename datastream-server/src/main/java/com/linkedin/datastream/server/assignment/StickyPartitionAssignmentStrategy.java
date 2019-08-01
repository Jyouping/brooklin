/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.server.assignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.DatastreamRuntimeException;
import com.linkedin.datastream.server.DatastreamGroupPartitionsMetadata;
import com.linkedin.datastream.server.DatastreamTask;
import com.linkedin.datastream.server.DatastreamTaskImpl;

/**
 *
 * The StickyPartitionAssignmentStrategy extends the StickyMulticastStrategy but allows to perform the partition
 * assignment. This StickyPartitionAssignmentStrategy creates new tasks and remove old tasks to accommodate the
 * change in partition assignment. The strategy is also "Sticky", i.e., it minimizes the potential task mutations.
 * The total number of tasks is also unchanged during this process.
 */
public class StickyPartitionAssignmentStrategy extends StickyMulticastStrategy {
  private static final Logger LOG = LoggerFactory.getLogger(StickyPartitionAssignmentStrategy.class.getName());

  /**
   * Constructor for StickyPartitionAssignmentStrategy
   * @param maxTasks Maximum number of {@link DatastreamTask}s to create out
   *                 of any {@link com.linkedin.datastream.common.Datastream}
   *                 if no value is specified for the "maxTasks" config property
   *                 at an individual datastream level.
   * @param imbalanceThreshold The maximum allowable difference in the number of tasks assigned
   *                           between any two {@link com.linkedin.datastream.server.Coordinator}
   *                           instances, before triggering a rebalance. The default is
   *                           {@value DEFAULT_IMBALANCE_THRESHOLD}.
   */
  public StickyPartitionAssignmentStrategy(Optional<Integer> maxTasks, Optional<Integer> imbalanceThreshold) {
    super(maxTasks, imbalanceThreshold);
  }
  /**
   * assign partitions to a particular datastream group
   *
   * @param currentAssignment the old assignment
   * @param datastreamPartitions the subscribed partitions for the particular datastream group
   * @return new assignment mapping
   */
  public Map<String, Set<DatastreamTask>> assignPartitions(Map<String,
      Set<DatastreamTask>> currentAssignment, DatastreamGroupPartitionsMetadata datastreamPartitions) {

    LOG.debug("old partition assignment info, assignment: {}", currentAssignment);

    String dgName = datastreamPartitions.getDatastreamGroup().getName();

    // Step 1: collect the # of tasks and figured out the unassigned partitions
    List<String> assignedPartitions = new ArrayList<>();
    int totalTaskCount = 0;
    for (Set<DatastreamTask> tasks : currentAssignment.values()) {
      Set<DatastreamTask> dgTask = tasks.stream().filter(t -> dgName.equals(t.getTaskPrefix())).collect(Collectors.toSet());
      dgTask.stream().forEach(t -> assignedPartitions.addAll(t.getPartitionsV2()));
      totalTaskCount += dgTask.size();
    }

    List<String> unassignedPartitions = new ArrayList<>(datastreamPartitions.getPartitions());
    unassignedPartitions.removeAll(assignedPartitions);

    int maxPartitionPerTask = datastreamPartitions.getPartitions().size() / totalTaskCount;
    // calculate how many tasks are allowed to have slightly more partitions
    final AtomicInteger remainder = new AtomicInteger(datastreamPartitions.getPartitions().size() % totalTaskCount);
    LOG.debug("maxPartitionPerTask {}, task count {}", maxPartitionPerTask, totalTaskCount);

    Collections.shuffle(unassignedPartitions);

    Map<String, Set<DatastreamTask>> newAssignment = new HashMap<>();

    //Step 2: generate new assignment. Assign unassigned partitions to tasks and create new task if there is
    // a partition change
    currentAssignment.keySet().forEach(instance -> {
      Set<DatastreamTask> tasks = currentAssignment.get(instance);
      Set<DatastreamTask> newAssignedTask = tasks.stream().map(task -> {
        if (!dgName.equals(task.getTaskPrefix())) {
          return task;
        } else {
          Set<String> newPartitions = new HashSet<>(task.getPartitionsV2());
          newPartitions.retainAll(datastreamPartitions.getPartitions());

          //We need to create new task if the partition is changed
          boolean partitionChanged = newPartitions.size() != task.getPartitionsV2().size();

          int allowedPartitions = remainder.get() > 0 ? maxPartitionPerTask + 1 : maxPartitionPerTask;

          while (newPartitions.size() < allowedPartitions && unassignedPartitions.size() > 0) {
            newPartitions.add(unassignedPartitions.remove(unassignedPartitions.size() - 1));
            partitionChanged = true;
          }

          if (remainder.get() > 0) {
            remainder.decrementAndGet();
          }

          if (partitionChanged) {
            return new DatastreamTaskImpl((DatastreamTaskImpl) task, newPartitions);
          } else {
            return task;
          }
        }
      }).collect(Collectors.toSet());
      newAssignment.put(instance, newAssignedTask);
    });
    LOG.info("new assignment info, assignment: {}, all partitions: {}", newAssignment,
        datastreamPartitions.getPartitions());

    partitionSanityChecks(newAssignment, datastreamPartitions);
    return newAssignment;
  }

  /**
   * Move a partition for a datastream group according to the targetAssignment. As we are only allowed to mutate the
   * task once. It follow the steps
   * Step 1) get the partitions that to be moved, and get their source task
   * Step 2) If the instance is the instance we want to move, we figure the task that we want to assign the task
   * Step 3) We mutate and compute new task if they belongs to these source tasks or if they are the
   * target task we want to move to
   *
   * @param currentAssignment the old assignment
   * @param targetAssignment the target assignment retrieved from Zookeeper
   * @param partitionsMetadata the subscribed partitions metadata received from connector
   * @return new assignment
   */
  public Map<String, Set<DatastreamTask>> movePartitions(Map<String, Set<DatastreamTask>> currentAssignment,
      Map<String, Set<String>> targetAssignment, DatastreamGroupPartitionsMetadata partitionsMetadata) {

    LOG.info("Move partition, task: {}, target assignment: {}, all partitions: {}", currentAssignment,
        targetAssignment, partitionsMetadata.getPartitions());

    String dgName = partitionsMetadata.getDatastreamGroup().getName();

    Set<String> allToReassignPartitions = new HashSet<>();
    targetAssignment.values().stream().forEach(allToReassignPartitions::addAll);
    allToReassignPartitions.retainAll(partitionsMetadata.getPartitions());

    //construct a map to store the tasks and if it contain the partitions that need to be released
    //map: <taskName, partitions that need to be released>
    Map<String, Set<String>> toReleasePartitionsTaskMap = new HashMap<>();
    Map<String, String> partitionToSourceTaskMap = new HashMap<>();

    //We first confirmed that the partitions in the target assignment can be removed, and we found its source task
    currentAssignment.keySet().stream().forEach(instance -> {
      Set<DatastreamTask> tasks = currentAssignment.get(instance);
      tasks.forEach(task -> {
        if (dgName.equals(task.getTaskPrefix())) {
          Set<String> toMovePartitions = new HashSet<>(task.getPartitionsV2());
          toMovePartitions.retainAll(allToReassignPartitions);
          toReleasePartitionsTaskMap.put(task.getDatastreamTaskName(), toMovePartitions);
          toMovePartitions.forEach(p -> partitionToSourceTaskMap.put(p, task.getDatastreamTaskName()));
        }
      });
    });

    Set<String> tasksToMutate = toReleasePartitionsTaskMap.keySet();
    Set<String> toReleasePartitions = new HashSet<>();
    toReleasePartitionsTaskMap.values().forEach(v -> toReleasePartitions.addAll(v));

    //Compute new assignment from the current assignment
    Map<String, Set<DatastreamTask>> newAssignment = new HashMap<>();

    currentAssignment.keySet().stream().forEach(instance -> {
      Set<DatastreamTask> tasks = currentAssignment.get(instance);

      // check if this instance has any partition to be moved
      final Set<String> toMovedPartitions = new HashSet<>();
      if (targetAssignment.containsKey(instance)) {
        toMovedPartitions.addAll(targetAssignment.get(instance).stream().filter(toReleasePartitions::contains).collect(Collectors.toSet()));
      }

      Set<DatastreamTask> dgTasks = tasks.stream().filter(t -> dgName.equals(t.getTaskPrefix()))
          .collect(Collectors.toSet());
      if (toMovedPartitions.size() > 0 && dgTasks.isEmpty()) {
        throw new DatastreamRuntimeException("No task is available in the target instance " + instance);
      }

      //find the target task to store the moved partition to store the target partitions
      final DatastreamTask targetTask = toMovedPartitions.size() > 0 ? dgTasks.stream()
          .reduce((task1, task2) -> task1.getPartitionsV2().size() < task2.getPartitionsV2().size() ? task1 : task2)
          .get() : null;

        //compute new assignment for that instance
        Set<DatastreamTask> newAssignedTask = tasks.stream().map(task -> {
          if (!dgName.equals(task.getTaskPrefix())) {
            return task;
          }
          boolean partitionChanged = false;
          List<String> newPartitions = new ArrayList<>(task.getPartitionsV2());
          Set<String> extraDependencies = new HashSet<>();

          // Release the partitions
          if (tasksToMutate.contains(task.getDatastreamTaskName())) {
            newPartitions.removeAll(toReleasePartitions);
            partitionChanged = true;
          }

          // move new partitions
          if (targetTask != null && task.getDatastreamTaskName().equals(targetTask.getDatastreamTaskName())) {
            newPartitions.addAll(toMovedPartitions);
            partitionChanged = true;
            // Add source task for these partitions into extra dependency
            toReleasePartitions.stream().forEach(p -> extraDependencies.add(partitionToSourceTaskMap.get(p)));
          }

          if (partitionChanged) {
            DatastreamTaskImpl newTask = new DatastreamTaskImpl((DatastreamTaskImpl) task, newPartitions);
            extraDependencies.forEach(t -> newTask.addDependency(t));
            return newTask;
          } else {
            return task;
          }
        }).collect(Collectors.toSet());
      newAssignment.put(instance, newAssignedTask);
    });

    LOG.info("assignment info, task: {}", newAssignment);
    partitionSanityChecks(newAssignment, partitionsMetadata);

    return newAssignment;
  }

  /**
   * check if the computed assignment contains all the partitions
   */
  private void partitionSanityChecks(Map<String, Set<DatastreamTask>> assignedTasks,
      DatastreamGroupPartitionsMetadata allPartitions) {
    int total = 0;

    List<String> unassignedPartitions = new ArrayList<>(allPartitions.getPartitions());
    String datastreamGroupName = allPartitions.getDatastreamGroup().getName();
    for (Set<DatastreamTask> tasksSet : assignedTasks.values()) {
      for (DatastreamTask task : tasksSet) {
        if (datastreamGroupName.equals(task.getTaskPrefix())) {
          total += task.getPartitionsV2().size();
          unassignedPartitions.removeAll(task.getPartitionsV2());
        }
      }
    }
    if (total != allPartitions.getPartitions().size()) {
      throw new DatastreamRuntimeException(String.format("Validation failed after assignment, assigned partitions "
          + "size: {} is not equal to all partitions size: {}", total, allPartitions.getPartitions().size()));
    }
    if (unassignedPartitions.size() > 0) {
      throw new DatastreamRuntimeException(String.format("Validation failed after assignment, "
          + "unassigned partition: {}", unassignedPartitions));
    }
  }
}
