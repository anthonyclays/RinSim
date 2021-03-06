/*
 * Copyright (C) 2011-2017 Rinde van Lon, imec-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.scenario.generator;

import static com.github.rinde.rinsim.util.StochasticSuppliers.constant;
import static com.google.common.base.Preconditions.checkArgument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import javax.annotation.Nonnull;

import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;

import com.github.rinde.rinsim.geom.Connection;
import com.github.rinde.rinsim.geom.Graph;
import com.github.rinde.rinsim.geom.MultiAttributeData;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.geom.TableGraph;
import com.github.rinde.rinsim.pdptw.common.ChangeConnectionSpeedEvent;
import com.github.rinde.rinsim.util.StochasticSupplier;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Utility class for creating {@link DynamicSpeedGenerator}s. The generator has
 * to be configured to produce the desired amount of shockwaves with the defined
 * behaviour and speed.
 *
 * A shockwave is related to the "shockwaves" experienced in traffic jams.
 * Similar to how throwing a rock in a pond results in waves, congested traffic
 * will cause the same. The influence of these shockwaves is congested traffic
 * moving across the network. In order to model realistic shockwaves, it is
 * required to first calculate the speed of the shockwave as well as determine
 * the flow delta in relation to the distance of the start of the shockwave.
 * This is necessary since the simulator doesn't support the notion of capacity
 * and density on connections. Both the speed and the flow delta are functions
 * represented by the shockwave speed (expanding for the initial shockwave
 * causing congestion, receding for the shockwave releasing the congestion) and
 * the behaviour function influencing the percentage of resistance to apply to
 * the connections. Finally shockwaves are bound in time with the duration
 * parameter, which can also be customised. The shockwave duration sets the
 * start time for the receding shockwave.
 * @author Vincent Van Gestel
 */
public final class DynamicSpeeds {

  static final int DEFAULT_NUM_OF_SHOCKWAVES = 1;
  static final double EIGHTY_PERCENT = 0.8d;
  static final double TWENTY_PERCENT = 0.2d;
  static final double TEN_KM_H = 0.002777777777777778d;
  static final double FIVE_KM = 5000d;
  static final long ONE_AND_A_HALF_HOUR = 5400000L;
  static final long THREE_HOUR = 10800000L;

  /**
   * The default number of shockwaves is 1.
   */
  static final StochasticSupplier<Integer> DEFAULT_NUMBER_OF_SHOCKWAVES =
    constant(DEFAULT_NUM_OF_SHOCKWAVES);

  static final Function<Double, Double> DEFAULT_BEHAVIOUR_FUNCTION =
    new Function<Double, Double>() {
      @Override
      public Double apply(@Nonnull Double input) {
        return Math.max(0,
          Math.min(EIGHTY_PERCENT / FIVE_KM * input + TWENTY_PERCENT, 1));
      }
    };
  /**
   * Linear function starting at 0.2d going to 1 over 1Km distance.
   */
  static final StochasticSupplier<Function<Double, Double>> DEFAULT_BEHAVIOUR =
    constant(DEFAULT_BEHAVIOUR_FUNCTION);

  /**
   * Start (0L).
   */
  static final StochasticSupplier<Long> DEFAULT_TIME = constant(0L);

  static final Function<Long, Double> DEFAULT_SPEED_FUNCTION =
    new Function<Long, Double>() {
      @Override
      public Double apply(Long input) {
        return TEN_KM_H;
      }
    };
  /**
   * Constant function of 0.0028 meters per milliseconds (10 Km/h).
   */
  static final StochasticSupplier<Function<Long, Double>> DEFAULT_RELATIVE_RECEDING_SPEED =
    constant(DEFAULT_SPEED_FUNCTION);
  static final StochasticSupplier<Function<Long, Double>> DEFAULT_RELATIVE_EXPANDING_SPEED =
    constant(DEFAULT_SPEED_FUNCTION);
  /**
   * 3 Hours.
   */
  static final StochasticSupplier<Long> DEFAULT_DURATION =
    constant(THREE_HOUR);

  /**
   * 1.5 Hours.
   */
  static final StochasticSupplier<Long> DEFAULT_WAIT_FOR_RECEDE_DURATION =
    constant(ONE_AND_A_HALF_HOUR);

  private static final DynamicSpeedGenerator ZERO_EVENT_GENERATOR =
    new DynamicSpeedGenerator() {
      @Override
      public ImmutableList<ChangeConnectionSpeedEvent> generate(long seed,
          long scenarioLength) {
        return ImmutableList.of();
      }
    };

  private DynamicSpeeds() {}

  /**
   * @return an immutable list of a single {@link DynamicSpeedGenerator}, which
   *         generates no events.
   */
  public static List<DynamicSpeedGenerator> zeroEvents() {
    return ImmutableList.of(ZERO_EVENT_GENERATOR);
  }

  /**
   * @return A newly constructed {@link Builder} for constructing
   *         {@link DynamicSpeedGenerator}s.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Generator of {@link ChangeConnectionSpeedEvent}s.
   * @author Vincent Van Gestel
   */
  public interface DynamicSpeedGenerator {
    /**
     * Should generate a list of {@link ChangeConnectionSpeedEvent}. Each event
     * indicates a change in maximum allowed speed on a connection.
     * @param seed The random seed to use for generating the vehicles.
     * @param scenarioLength The length of the scenario for which the events are
     *          generated.
     * @return A list of events.
     */
    ImmutableList<ChangeConnectionSpeedEvent> generate(long seed,
        long scenarioLength);
  }

  /**
   * A builder for constructing {@link DynamicSpeedGenerator}s.
   * @author Vincent Van Gestel
   */
  public static class Builder {
    Optional<Graph<MultiAttributeData>> graph;
    StochasticSupplier<Integer> numberOfShockwaves;
    Optional<StochasticSupplier<Connection<MultiAttributeData>>> startingConnectionSupplier;
    StochasticSupplier<Long> eventTimesSupplier;
    StochasticSupplier<Function<Double, Double>> behaviourSupplier;
    StochasticSupplier<Long> eventDurationSupplier;
    StochasticSupplier<Long> recedeWaitDurationSupplier;
    StochasticSupplier<Function<Long, Double>> expandingSpeedSupplier;
    StochasticSupplier<Function<Long, Double>> recedingSpeedSupplier;

    Builder() {
      numberOfShockwaves = DEFAULT_NUMBER_OF_SHOCKWAVES;
      startingConnectionSupplier = Optional.absent();
      eventTimesSupplier = DEFAULT_TIME;
      graph = Optional.absent();
      behaviourSupplier = DEFAULT_BEHAVIOUR;
      eventDurationSupplier = DEFAULT_DURATION;
      recedeWaitDurationSupplier = DEFAULT_WAIT_FOR_RECEDE_DURATION;
      expandingSpeedSupplier = DEFAULT_RELATIVE_EXPANDING_SPEED;
      recedingSpeedSupplier = DEFAULT_RELATIVE_RECEDING_SPEED;
    }

    /**
     * Sets the number of shockwaves that are to be created by the generator.
     * Default value: 1. All values returned by the {@link StochasticSupplier}
     * must be greater than <code>0</code>.
     * @param num The supplier to draw numbers from.
     * @return This, as per the builder pattern.
     */
    public Builder numberOfShockwaves(StochasticSupplier<Integer> num) {
      numberOfShockwaves = num;
      return this;
    }

    /**
     * Sets the starting connections for the to be generated shockwaves. By
     * default the start positions of any shockwave is a random connection on
     * the graph.
     * @param conn The supplier to draw connections from.
     * @return This, as per the builder pattern.
     */
    public Builder startConnections(
        StochasticSupplier<Connection<MultiAttributeData>> conn) {
      startingConnectionSupplier = Optional.of(conn);
      return this;
    }

    /**
     * Sets the start connections of all generated shockwaves to be random. This
     * is the default behavior.
     * @return This, as per the builder pattern.
     */
    public Builder randomStartConnections() {
      startingConnectionSupplier = Optional.absent();
      return this;
    }

    /**
     * Sets the graph to be used for the generation of events.
     * @param g The supplier from which to draw the graph.
     * @return This, as per the builder pattern.
     */
    public Builder withGraph(Graph<MultiAttributeData> g) {
      graph = Optional.of(g);
      return this;
    }

    /**
     * Vehicles Sets the behaviour for the shockwave. The behaviour indicates at
     * which capacity a connection can operate (driving speed relative to
     * maximal allowed speed on said connection), at every possible distance
     * from the original location. The functions should return exactly "1" when
     * it should have no effect on the connection.
     * @param behaviour The function describing the relation between distance
     *          travelled and speed drop off.
     * @return This, as per the builder pattern.
     */
    public Builder shockwaveBehaviour(
        StochasticSupplier<Function<Double, Double>> behaviour) {
      behaviourSupplier = behaviour;
      return this;
    }

    /**
     * Sets the function declaring the at which speed the shockwave propagates
     * through the graph relative to the time elapsed. The functions should
     * return exactly "0" when it should stop. The unit of this speed function
     * should use the same unit of the distance and the time of the simulation.
     * @param speed The function describing the relation between elapsed time of
     *          the shockwave to the speed at which it travels.
     * @return This, as per the builder pattern.
     */
    public Builder shockwaveExpandingSpeed(
        StochasticSupplier<Function<Long, Double>> speed) {
      expandingSpeedSupplier = speed;
      return this;
    }

    /**
     * Sets the function declaring the at which speed the shockwave recedes
     * through the graph relative to the time elapsed. The functions should
     * return exactly "0" when it should stop. The unit of this speed function
     * should use the same unit of the distance and the time of the simulation.
     * @param speed The function describing the relation between elapsed time of
     *          the shockwave to the speed at which it travels.
     * @return This, as per the builder pattern.
     */
    public Builder shockwaveRecedingSpeed(
        StochasticSupplier<Function<Long, Double>> speed) {
      recedingSpeedSupplier = speed;
      return this;
    }

    /**
     * Sets the duration of a shockwave before it starts receding. Default
     * value: 5400000L, which is 1.5 hours.
     * @param times The supplier from which to draw times.
     * @return This, as per the builder pattern.
     */
    public Builder shockwaveWaitForRecedeDurations(
        StochasticSupplier<Long> times) {
      recedeWaitDurationSupplier = times;
      return this;
    }

    /**
     * Sets the total duration an expanding shockwave is allowed to last.
     * Default value: 10800000L, which is 3 hours.
     * @param times The supplier from which to draw times.
     * @return This, as per the builder pattern.
     */
    public Builder shockwaveEventDurations(StochasticSupplier<Long> times) {
      eventDurationSupplier = times;
      return this;
    }

    /**
     * Sets the duration times of the total event generated by the generator.
     * Default value: 0, this means that the event will commence at the
     * beginning of the scenario.
     * @param times The supplier from which to draw times.
     * @return This, as per the builder pattern.
     */
    public Builder creationTimes(StochasticSupplier<Long> times) {
      eventTimesSupplier = times;
      return this;
    }

    /**
     * @return A newly constructed {@link DynamicSpeedGenerator} as specified by
     *         this builder.
     */
    public DynamicSpeedGenerator build() {
      return new DefaultDynamicSpeedGenerator(this);
    }
  }

  private static class DefaultDynamicSpeedGenerator
      implements DynamicSpeedGenerator {

    // Generator variables
    private final Optional<Graph<MultiAttributeData>> graph;
    private final StochasticSupplier<Integer> numberOfShockwaves;
    private final Optional<StochasticSupplier<Connection<MultiAttributeData>>> startingConnectionSupplier;
    private final StochasticSupplier<Long> eventTimesSupplier;
    private final StochasticSupplier<Function<Double, Double>> behaviourSupplier;
    private final StochasticSupplier<Long> eventDurationSupplier;
    private final StochasticSupplier<Long> recedeWaitDurationSupplier;
    private final StochasticSupplier<Function<Long, Double>> expandingSpeedSupplier;
    private final StochasticSupplier<Function<Long, Double>> recedingSpeedSupplier;
    private final RandomGenerator rng;

    // Shockwave variables
    private Map<Connection<MultiAttributeData>, List<ChangeConnectionSpeedEvent>> expansionMap;
    private Graph<MultiAttributeData> affectedGraph;
    private Set<Connection<MultiAttributeData>> leafNodes;

    DefaultDynamicSpeedGenerator(Builder b) {
      numberOfShockwaves = b.numberOfShockwaves;
      startingConnectionSupplier = b.startingConnectionSupplier;
      eventTimesSupplier = b.eventTimesSupplier;
      graph = b.graph;
      behaviourSupplier = b.behaviourSupplier;
      eventDurationSupplier = b.eventDurationSupplier;
      recedeWaitDurationSupplier = b.recedeWaitDurationSupplier;
      expandingSpeedSupplier = b.expandingSpeedSupplier;
      recedingSpeedSupplier = b.recedingSpeedSupplier;
      rng = new MersenneTwister();
    }

    @Override
    public ImmutableList<ChangeConnectionSpeedEvent> generate(long seed,
        long scenarioLength) {
      final ImmutableList.Builder<ChangeConnectionSpeedEvent> builder =
        ImmutableList.builder();

      final int numShockwaves = numberOfShockwaves.get(rng.nextLong());
      checkArgument(numShockwaves > 0,
        "The numberOfShockwaves supplier must generate values > 0, found %s.",
        numShockwaves);
      for (int i = 0; i < numShockwaves; i++) {
        final Connection<MultiAttributeData> conn;

        if (startingConnectionSupplier.isPresent()) {
          conn = startingConnectionSupplier.get().get(rng.nextLong());
        } else {
          conn = graph.get().getRandomConnection(rng);
        }

        // Emulate shockwave starting in origin
        final long startingTime = eventTimesSupplier.get(rng.nextLong());

        expansionMap = new HashMap<>();
        affectedGraph = new TableGraph<>();
        leafNodes = new HashSet<>();

        final Queue<ShockwaveSimulation> shockwave = new LinkedList<>();
        shockwave.offer(
          new ShockwaveSimulation(shockwave, conn, conn, 0, 0, startingTime,
            startingTime + recedeWaitDurationSupplier.get(rng.nextLong()), 0,
            scenarioLength));

        while (!shockwave.isEmpty()) {
          builder.addAll(shockwave.poll().simulateForwardRecedingShockwave());
        }

      }
      return builder.build();
    }

    class ShockwaveSimulation {

      private final Queue<ShockwaveSimulation> shockwave;
      private final Connection<MultiAttributeData> origin;
      private final Connection<MultiAttributeData> conn;
      private final long relExpandingTimestamp;
      private final long relRecedingTimestamp;
      private final long actualExpandingTimestamp;
      private final long actualRecedingTimestamp;
      private final double distance;
      private final long scenarioLength;

      /**
       * @param shockwaveQueue The queue of shockwaves steps, while this is non
       *          empty, more simulation steps should be done. An empty queue
       *          indicates that both the expanding and the receding shockwave
       *          have died.
       * @param originConn The previous connection to be handled.
       * @param currentConn The current connection to handle in this shockwave
       *          simulation step.
       * @param relativeExpandingTimestamp The time of the shockwave before
       *          expanding over the current connection.
       * @param relativeRecedingTimestamp The time of the shockwave before
       *          receding over the current connection.
       * @param currentExpandingTimestamp The time of the simulation before
       *          expanding over current connection.
       * @param currentRecedingTimestamp The time of the simulation before
       *          receding over the current connection.
       * @param totalDistance Total distance travelled.
       * @param totalScenarioLength Total duration of the scenario.
       */
      ShockwaveSimulation(Queue<ShockwaveSimulation> shockwaveQueue,
          Connection<MultiAttributeData> originConn,
          Connection<MultiAttributeData> currentConn,
          long relativeExpandingTimestamp,
          long relativeRecedingTimestamp, long currentExpandingTimestamp,
          long currentRecedingTimestamp, double totalDistance,
          long totalScenarioLength) {
        this.shockwave = shockwaveQueue;
        this.origin = originConn;
        this.conn = currentConn;
        this.relExpandingTimestamp = relativeExpandingTimestamp;
        this.relRecedingTimestamp = relativeRecedingTimestamp;
        this.actualExpandingTimestamp = currentExpandingTimestamp;
        this.actualRecedingTimestamp = currentRecedingTimestamp;
        this.distance = totalDistance;
        this.scenarioLength = totalScenarioLength;
      }

      /**
       * Simulates a shockwave expanding and receding from the head. All events
       * directly caused by the parameters stored in the current simulation step
       * are returned. If other events still need to be calculated, then they
       * are added to the shockwave queue.
       * @return the events caused by this iteration of expansion.
       */
      private List<ChangeConnectionSpeedEvent> simulateForwardRecedingShockwave() {
        final List<ChangeConnectionSpeedEvent> events = new ArrayList<>();
        @Nonnull
        final Double factor = behaviourSupplier.get(rng.nextLong())
          .apply(distance + conn.getLength() / 2);
        final Double factorInverse = 1 / factor;
        @Nonnull
        final Double forwardSpeed =
          expandingSpeedSupplier.get(rng.nextLong())
            .apply(relExpandingTimestamp);
        @Nonnull
        final Double recedingSpeed =
          recedingSpeedSupplier.get(rng.nextLong()).apply(relRecedingTimestamp);

        long nextRelExTimestamp = relExpandingTimestamp;
        long nextActualExTimestamp = actualExpandingTimestamp;

        long nextRelReTimestamp = relRecedingTimestamp;
        long nextActualReTimestamp = actualRecedingTimestamp;

        if (forwardSpeed != 0) {
          nextRelExTimestamp =
            (long) (relExpandingTimestamp + conn.getLength() / forwardSpeed);
          nextActualExTimestamp =
            (long) (actualExpandingTimestamp + conn.getLength() / forwardSpeed);
        }

        if (recedingSpeed != 0) {
          nextRelReTimestamp =
            (long) (relRecedingTimestamp + conn.getLength() / recedingSpeed);
          nextActualReTimestamp =
            (long) (actualRecedingTimestamp + conn.getLength() / recedingSpeed);
        }

        final long exJump =
          (nextActualExTimestamp - actualExpandingTimestamp) / 2;
        final long reJump =
          (nextActualReTimestamp - actualRecedingTimestamp) / 2;

        // Check Stop conditions
        // Stop if shockwave has no effect (factor == 1)
        // OR if shockwave burned out (speed == 0)
        // OR if shockwave duration reached
        // OR if recede caught up
        // OR if cycle???
        if (factor == 1 || forwardSpeed == 0
          || nextRelExTimestamp >= eventDurationSupplier
            .get(rng.nextLong())
          || actualExpandingTimestamp + exJump >= actualRecedingTimestamp
            + reJump
          || expansionMap.containsKey(conn)) {
          // Origin was last affected node
          leafNodes.add(origin);
        } else {
          // EXPAND

          // Add conn
          final ChangeConnectionSpeedEvent newExEvent =
            ChangeConnectionSpeedEvent.create(actualExpandingTimestamp + exJump,
              conn, factor);
          events.add(newExEvent);

          // Keep track of events linked to connections
          if (expansionMap.containsKey(conn)) {
            // Cycle, add to existing list
            final List<ChangeConnectionSpeedEvent> eventList =
              expansionMap.get(conn);
            eventList.add(newExEvent);
            expansionMap.put(conn, eventList);
          } else {
            // New connection
            expansionMap.put(conn, Lists.newArrayList(newExEvent));
            affectedGraph.addConnection(conn);
          }

          // RECEDE

          // Recede only if
          // AND shockwave hasn't burned out (speed == 0)
          if (recedingSpeed != 0) {
            // Remove conn
            final ChangeConnectionSpeedEvent newReEvent =
              ChangeConnectionSpeedEvent.create(
                actualRecedingTimestamp + reJump,
                conn, factorInverse);
            events.add(newReEvent);
          }

          // BRANCH

          final Collection<Point> nextFroms = graph.get()
            .getIncomingConnections(conn.from());
          // Check dead end -> no need to continue branching
          if (nextFroms.isEmpty() || nextFroms.size() == 1
            && nextFroms.iterator().next().equals(conn.to())) {
            leafNodes.add(conn);
            return events;
          }
          for (final Point nextFrom : nextFroms) {
            if (nextFrom.equals(conn.to())) {
              // No backtracking in the other direction
              continue;
            }
            final Connection<MultiAttributeData> nextConn =
              graph.get().getConnection(nextFrom, conn.from());
            shockwave.offer(new ShockwaveSimulation(shockwave, conn, nextConn,
              nextRelExTimestamp, nextRelReTimestamp, nextActualExTimestamp,
              nextActualReTimestamp,
              distance + conn.getLength(), scenarioLength));
          }
        }
        return events;
      }
    }
  }
}
