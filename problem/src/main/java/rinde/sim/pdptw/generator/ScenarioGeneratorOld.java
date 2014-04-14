/**
 * 
 */
package rinde.sim.pdptw.generator;

import static com.google.common.base.Preconditions.checkArgument;
import static rinde.sim.pdptw.generator.Metrics.travelTime;

import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.math3.random.RandomGenerator;

import rinde.sim.core.graph.Point;
import rinde.sim.core.model.pdp.PDPScenarioEvent;
import rinde.sim.pdptw.common.AddDepotEvent;
import rinde.sim.pdptw.common.AddParcelEvent;
import rinde.sim.pdptw.common.ParcelDTO;
import rinde.sim.pdptw.common.VehicleDTO;
import rinde.sim.pdptw.generator.loc.LocationGenerator;
import rinde.sim.pdptw.generator.loc.NormalLocationsGenerator;
import rinde.sim.pdptw.generator.times.ArrivalTimeGenerator;
import rinde.sim.pdptw.generator.tw.ProportionateUniformTWGenerator;
import rinde.sim.pdptw.generator.tw.TimeWindowGenerator;
import rinde.sim.pdptw.generator.vehicles.HomogenousVehicleGenerator;
import rinde.sim.pdptw.generator.vehicles.VehicleGenerator;
import rinde.sim.scenario.Scenario;
import rinde.sim.scenario.ScenarioBuilder;
import rinde.sim.scenario.ScenarioBuilder.ScenarioCreator;
import rinde.sim.scenario.TimedEvent;
import rinde.sim.util.TimeWindow;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.math.DoubleMath;

/**
 * ScenarioGenerator generates {@link Scenario}s of a specific problem class.
 * Instances can be obtained via {@link #builder()}.
 * 
 * @param <T> The type of scenario that is generated by this generator.
 * @author Rinde van Lon <rinde.vanlon@cs.kuleuven.be>
 */
public class ScenarioGeneratorOld<T extends Scenario> {

  private final ArrivalTimeGenerator arrivalTimesGenerator;
  private final LocationGenerator locationsGenerator;
  private final TimeWindowGenerator timeWindowGenerator;
  private final VehicleGenerator vehicleGenerator;

  private final long length;
  private final Point depotLocation;
  private final Point min;
  private final Point max;
  private final long tickSize;
  private final long serviceTime;
  @Nullable
  private final ScenarioFactory<T> scenarioCreator;
  private final Predicate<Scenario> requirements;

  ScenarioGeneratorOld(
      Builder<T> builder) {

    scenarioCreator = builder.scenarioFactory;
    arrivalTimesGenerator = builder.arrivalTimesGenerator;
    locationsGenerator = builder.locationsGenerator;
    timeWindowGenerator = builder.timeWindowGenerator;
    vehicleGenerator = builder.vehicleGenerator;
    depotLocation = builder.depotLocation;
    length = builder.scenarioLength;
    requirements = builder.baseRequirement;
    tickSize = builder.tickSize;
    serviceTime = builder.serviceTime;
    min = builder.min;
    max = builder.max;
  }

  public T generate(RandomGenerator rng) {
    return generate(rng, 1).scenarios.get(0);
  }

  public ScenarioSet<T> generate(RandomGenerator rng, int num) {
    return generate(rng, num, -1);
  }

  public ScenarioSet<T> generate(RandomGenerator rng, int num,
      long maxCompTime) {

    final long start = System.currentTimeMillis();
    long duration;

    final ImmutableList.Builder<T> scenarioListBuilder = ImmutableList
        .builder();
    int foundScenarios = 0;
    int i = 0;
    do {
      final T s = doGenerate(rng, foundScenarios);
      i++;
      if (requirements.apply(s)) {
        scenarioListBuilder.add(s);
        foundScenarios++;
      }
      duration = System.currentTimeMillis() - start;
    } while ((maxCompTime < 0 || duration < maxCompTime)
        && foundScenarios < num);

    return new ScenarioSet<T>(scenarioListBuilder.build(), duration, i + 1);
  }

  T doGenerate(RandomGenerator rng, int num) {
    final ImmutableList<Long> times = convert(arrivalTimesGenerator
        .generate(rng));
    final ImmutableList<Point> locations = locationsGenerator.generate(
        times.size(), rng);
    int index = 0;

    final ScenarioBuilder sb = new ScenarioBuilder(PDPScenarioEvent.ADD_DEPOT,
        PDPScenarioEvent.ADD_PARCEL, PDPScenarioEvent.ADD_VEHICLE,
        PDPScenarioEvent.TIME_OUT);
    sb.addEvent(new AddDepotEvent(-1, depotLocation));
    sb.addEvents(vehicleGenerator.generate(rng));

    for (final long time : times) {
      final Point pickup = locations.get(index++);
      final Point delivery = locations.get(index++);
      final ImmutableList<TimeWindow> tws = timeWindowGenerator.generate(time,
          pickup, delivery, rng);
      sb.addEvent(new AddParcelEvent(
          ParcelDTO.builder(pickup, delivery)
              .pickupTimeWindow(tws.get(0))
              .deliveryTimeWindow(tws.get(1))
              .neededCapacity(0)
              .arrivalTime(time)
              .serviceDuration(serviceTime)
              .build()));
    }
    sb.addEvent(new TimedEvent(PDPScenarioEvent.TIME_OUT, length));
    if (scenarioCreator != null) {
      return sb.build(new FactoryWrapper<T>(scenarioCreator, this, num));
    }
    else {
      return (T) sb.build();
    }
  }

  public static ImmutableList<Long> convert(List<Double> in) {
    final ImmutableList.Builder<Long> builder = ImmutableList.builder();
    for (final double d : in) {
      builder.add(DoubleMath.roundToLong(d, RoundingMode.HALF_UP));
    }
    return builder.build();
  }

  public long getScenarioLength() {
    return length;
  }

  public Point getMinPoint() {
    return min;
  }

  public Point getMaxPoint() {
    return max;
  }

  public long getTickSize() {
    return tickSize;
  }

  public static Builder<Scenario> builder() {
    return new Builder<Scenario>(null);
  }

  public static <T extends Scenario> Builder<T> builder(
      ScenarioFactory<T> scenarioFactory) {
    return new Builder<T>(scenarioFactory);
  }

  public static class Builder<T extends Scenario> {

    /**
     * The minimum interval between the arrival of an order and the opening of
     * the first time window. Default: 30 minutes.
     */
    public static final long DEFAULT_MIN_RESPONSE_TIME = 30;

    /**
     * The default vehicle speed in kilometer per hour.
     */
    public static final double DEFAULT_VEHICLE_SPEED = 30d;

    // this is actually irrelevant since parcels are weightless
    private static final int VEHICLE_CAPACITY = 1;
    private static final long DEFAULT_SERVICE_TIME = 5;
    private static final long DEFAULT_TICK_SIZE = 1000L;

    @Nullable
    final ScenarioFactory<T> scenarioFactory;

    int vehicles;
    double size;
    double announcementIntensity;
    double ordersPerAnnouncement;
    long scenarioLength;
    long minimumResponseTime;
    double vehicleSpeed;
    Predicate<Scenario> baseRequirement;
    final long tickSize;
    final long serviceTime;

    Point depotLocation;
    Point min;
    Point max;

    @Nullable
    ArrivalTimeGenerator arrivalTimesGenerator;
    @Nullable
    LocationGenerator locationsGenerator;
    @Nullable
    TimeWindowGenerator timeWindowGenerator;
    @Nullable
    VehicleGenerator vehicleGenerator;

    Builder(@Nullable ScenarioFactory<T> sf) {
      scenarioFactory = sf;
      vehicles = -1;
      size = -1;
      announcementIntensity = -1;
      ordersPerAnnouncement = -1;
      scenarioLength = -1;
      tickSize = DEFAULT_TICK_SIZE;
      minimumResponseTime = DEFAULT_MIN_RESPONSE_TIME;
      vehicleSpeed = DEFAULT_VEHICLE_SPEED;
      serviceTime = DEFAULT_SERVICE_TIME;
      baseRequirement = Predicates.alwaysTrue();
    }

    /**
     * Sets the global intensity of orders and sets the dynamism which defines
     * the ratio between orders and announcements. This method overrides any
     * previous calls to {@link #setAnnouncementIntensityPerKm2(double)} and
     * {@link #setOrdersPerAnnouncement(double)}.
     * @param intensity The average number of orders per km2 per hour.
     * @param dynamism Dynamism in a scenario is defined as the number of
     *          changes in a problem relative to the number of tasks. More
     *          formally: <code>dynamism = announcements / orders</code>.
     * @return This, as per the builder pattern.
     */
    @Deprecated
    public Builder<T> setOrderIntensityAndDynamism(double intensity,
        double dynamism) {
      checkArgument(dynamism > 0d && dynamism <= 1d,
          "Dynamism %s must be in (0,1]");
      return setAnnouncementIntensityPerKm2(intensity * dynamism).
          setOrdersPerAnnouncement(1d / dynamism);
    }

    /**
     * Sets number of announcements per square kilometer for generated
     * scenarios. An announcement is a call from a customer, an announcement can
     * be comprised of one or more orders (a pickup-and-delivery request). The
     * ratio between announcements and orders can be set via
     * {@link #setOrdersPerAnnouncement(double)}. The actual number of
     * announcements per hour in a scenario is calculated by the following
     * formula:
     * <p>
     * <code>numAnnouncementsPerHour = (size*size) * intensity</code>
     * 
     * The <code>numAnnouncementsPerHour</code> variable is used as the
     * intensity (lambda) for a Poisson process which generates the actual
     * announcements.
     * 
     * @param intensity Announcement intensity, must be a positive number.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setAnnouncementIntensityPerKm2(double intensity) {
      checkArgument(intensity > 0d, "Intensity must be a positive number.");
      announcementIntensity = intensity;
      return this;
    }

    /**
     * The total length of the scenario, during this time new announcements can
     * still arrive. Scenarios are constructed in such a way that
     * pickup-and-delivery of each order in each announcement is in
     * <i>theory</i> feasible. Practical feasibility depends on the actual
     * number of available vehicles and the efficiency of the algorithms used.
     * @param minutes The length of the scenario in minutes, must be greater
     *          than {@link #DEFAULT_MIN_RESPONSE_TIME}.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setScenarioLength(long minutes) {
      return setScenarioLength(minutes, DEFAULT_MIN_RESPONSE_TIME);
    }

    /**
     * The total length of the scenario, during this time new announcements can
     * still arrive. Scenarios are constructed in such a way that
     * pickup-and-delivery of each order in each announcement is in
     * <i>theory</i> feasible. Practical feasibility depends on the actual
     * number of available vehicles and the efficiency of the algorithms used.
     * @param minutes The length of the scenario in minutes, must be greater
     *          than minResponseTime.
     * @param minResponseTime The minimum interval between the arrival of an
     *          order and the opening of the first time window.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setScenarioLength(long minutes, long minResponseTime) {
      checkArgument(minutes > minimumResponseTime,
          "Scenario length must be greater than minResponseTime %s.",
          minimumResponseTime);
      scenarioLength = minutes;
      minimumResponseTime = minResponseTime;
      return this;
    }

    /**
     * Defines the ratio of orders per announcement. For example, if the ratio
     * is 1.2, there will be announcements with either 1 or 2 orders:
     * <ul>
     * <li>1 order [80%]</li>
     * <li>2 orders [20%]</li>
     * </ul>
     * Scenarios generated will follow this distribution as close as possible.
     * @param orders Ratio of orders per announcement. Must be >= 1.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setOrdersPerAnnouncement(double orders) {
      checkArgument(orders >= 1d, "Orders per announcement must be >= 1.");
      ordersPerAnnouncement = orders;
      return this;
    }

    /**
     * Sets the scale property of the scenarios to be generated. This method
     * allows to define a <i>vehicle density</i> for a set of scenarios while
     * varying the actual size of the area of the scenarios. The actual number
     * of vehicles used in a scenario is defined by the following formula:
     * <p>
     * <code> max(1,round(numVehiclesKM2 * (size*size))) </code>
     * 
     * @param numVehiclesKM2 The number of vehicles per km2. Must be a positive
     *          number. Note that the actual <i>minimum</i> number of vehicles
     *          is 1. E.g. in case <code>numVehiclesKM2 = .2</code> and
     *          <code>size = 1</code> the calculated number of vehicles would be
     *          .2 which would normally be rounded down to 0. However, since 0
     *          vehicles is impractical, the number of vehicles is defined as
     *          being 1 in this case.
     * @param size Indicates the size of the area in kilometers. Must be a
     *          positive number. This number equals the width and height of the
     *          resulting square. As such the area of the resulting square is
     *          defined as <code>size * size</code>.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setScale(double numVehiclesKM2, double size) {
      checkArgument(numVehiclesKM2 > 0d,
          "Number of vehicles per km2 must be a positive number.");
      checkArgument(size > 0d, "Size must be a positive number.");
      this.size = size;
      final double area = size * size;
      vehicles = Math.max(1,
          DoubleMath.roundToInt(numVehiclesKM2 * area, RoundingMode.HALF_DOWN));

      depotLocation = new Point(size / 2, size / 2);
      min = new Point(0, 0);
      max = new Point(size, size);

      return this;
    }

    /**
     * Sets the vehicle speed. The default vehicle speed is
     * {@link #DEFAULT_VEHICLE_SPEED}.
     * @param speedInKmH The vehicle speed in km/h. Must be positive.
     * @return This, as per the builder pattern.
     */
    public Builder<T> setVehicleSpeed(double speedInKmH) {
      checkArgument(speedInKmH > 0d, "Vehicle speed must be postive.");
      vehicleSpeed = speedInKmH;
      return this;
    }

    public Builder<T> addRequirement(Predicate<Scenario> requirement) {
      baseRequirement = Predicates.and(baseRequirement, requirement);
      return this;
    }

    public Builder<T> addRequirements(Iterable<Predicate<Scenario>> requirements) {
      baseRequirement = Predicates.and(baseRequirement,
          Predicates.and(requirements));
      return this;
    }

    public Builder<T> setArrivalTimesGenerator(ArrivalTimeGenerator atg) {
      arrivalTimesGenerator = atg;
      return this;
    }

    public Builder<T> setTimeWindowGenerator(TimeWindowGenerator twg) {
      timeWindowGenerator = twg;
      return this;
    }

    /**
     * @return A newly created {@link ScenarioGeneratorOld} instance.
     */
    public ScenarioGeneratorOld<T> build() {
      checkArgument(vehicles > 0 && size > 0,
          "Cannot build generator, scale needs to be set via setScale(double,double).");
      // checkArgument(
      // ordersPerAnnouncement > 0,
      // "Cannot build generator, orders need to be set via setOrdersPerAnnouncement(double).");
      checkArgument(
          scenarioLength > 0,
          "Cannot build generator, scenario length needs to be set via setScenarioLength(long).");
      // checkArgument(
      // announcementIntensity > 0,
      // "Cannot build generator, announcement intensity needs to be set via setAnnouncementIntensityPerKm2(double).");

      final double area = size * size;
      final double globalAnnouncementIntensity = area * announcementIntensity;

      // this computes the traveltime it would take to travel from one of
      // the corners of the environment to another corner of the
      // environment and then back to the depot.
      final long time1 = travelTime(min, max, vehicleSpeed);
      final long time2 = travelTime(max, depotLocation, vehicleSpeed);
      final long travelTime = time1 + time2;

      // this is the maximum *theoretical* time that is required to
      // service an order. In this context, theoretical means: given
      // enough resources (vehicles).
      final long maxRequiredTime = minimumResponseTime + travelTime
          + (2 * serviceTime);
      final long latestOrderAnnounceTime = scenarioLength - maxRequiredTime;

      // TODO this can be improved by allowing orders which are closer to
      // the depot for a longer time. This could be implemented by simply
      // rejecting any orders which are not feasible. This could be a
      // reasonable company policy in case minimizing overTime is more
      // important than customer satisfaction.
      checkArgument(
          scenarioLength > maxRequiredTime,
          "The scenario length must be long enough such that there is enough time for a vehicle to service a pickup at one end of the environment and to service a delivery at an opposite end of the environment and be back in time at the depot.");

      final VehicleDTO vehicleDto = new VehicleDTO(depotLocation,
          vehicleSpeed,
          VEHICLE_CAPACITY, new TimeWindow(0, scenarioLength));

      if (arrivalTimesGenerator == null) {
        throw new UnsupportedOperationException("not yet implemented");
        // arrivalTimesGenerator = new PoissonProcessArrivalTimes(
        // latestOrderAnnounceTime,
        // globalAnnouncementIntensity, ordersPerAnnouncement);
      }
      locationsGenerator =
          new NormalLocationsGenerator(size, .15, .05);
      if (timeWindowGenerator == null) {
        timeWindowGenerator =
            new ProportionateUniformTWGenerator(depotLocation,
                scenarioLength,
                serviceTime * 60000, minimumResponseTime * 60000, vehicleSpeed);
      }
      vehicleGenerator = new HomogenousVehicleGenerator(vehicles, vehicleDto);

      return new ScenarioGeneratorOld<T>(this);
    }
  }

  public static final class ScenarioSet<T> {
    public final ImmutableList<T> scenarios;
    public final long computationTime;
    public final long generatedScenarios;

    ScenarioSet(ImmutableList<T> s, long compTime, long scen) {
      scenarios = s;
      computationTime = compTime;
      generatedScenarios = scen;
    }
  }

  public interface ScenarioFactory<T extends Scenario> {
    T create(List<TimedEvent> events, ScenarioGeneratorOld<T> generator,
        int instanceNumber);
  }

  static class FactoryWrapper<T extends Scenario> implements ScenarioCreator<T> {
    private final ScenarioFactory<T> creator;
    private final ScenarioGeneratorOld<T> generator;
    private final int instanceNumber;

    FactoryWrapper(ScenarioFactory<T> c, ScenarioGeneratorOld<T> g,
        int num) {
      creator = c;
      generator = g;
      instanceNumber = num;
    }

    @Override
    public T create(List<TimedEvent> eventList, Set<Enum<?>> eventTypes) {
      return creator.create(eventList, generator, instanceNumber);
    }
  }
}
