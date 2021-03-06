package de.uniwue.VNFP.algo;

import de.uniwue.VNFP.gui.Gui;
import de.uniwue.VNFP.model.*;
import de.uniwue.VNFP.model.factory.*;
import de.uniwue.VNFP.model.log.Debugger;
import de.uniwue.VNFP.model.log.PSAEventLogger;
import de.uniwue.VNFP.model.solution.Solution;
import de.uniwue.VNFP.model.solution.TrafficAssignment;
import de.uniwue.VNFP.model.solution.overview.NodeOverview;
import de.uniwue.VNFP.util.Config;
import de.uniwue.VNFP.model.solution.VnfInstances;
import de.uniwue.VNFP.util.HashWrapper;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * This class implements the main optimization procedure, inspired by PSA.
 *
 * @author alex
 */
public class PSA {
    public final NetworkGraph ng;
    public final VnfLib lib;
    public final TrafficRequest[] reqs;
    public final ProblemInstance pi;

    public final int s;
    public final int m;
    public final double tmax;
    public final double tmin;
    public final double rho;
    public final long seed;
    public final double runtime;

    private LinkedList<PSAEventLogger> loggers;
    private boolean executed;
    private Random r;

    private int[] dominatingNeighboursLastTemp;
    private int[] incomparableNeighboursLastTemp;
    private int[] iterationsLastTemp;

    private Solution[] initialSolutions;

    /**
     * Reads the configuration from the given path and executes one optimization run with these settings.
     * If no config file is present when running this method, a new example file is created instead and the execution aborts.
     *
     * @param configPath The path towards the config javascript file. May be null for default path {@code "config.js"}.
     * @return The resulting frontier of Pareto-optimal placements.
     * @throws IOException          If reading the config failed.
     * @throws ExecutionException   If errors during concurrency appear.
     * @throws InterruptedException If errors during concurrency appear.
     */
    public static ParetoFrontier runPSA(String configPath) throws IOException, ExecutionException, InterruptedException {
        // Initialize Config
        if (configPath == null) {
            configPath = "config.js";
        }
        if (!Files.exists(Paths.get(configPath))) {
            System.out.println("Config file '" + configPath + "' not found.");
            System.out.println("Creating default config file in:");
            System.out.println(Paths.get(configPath).toAbsolutePath().toString());

            Config.getInstance().writeConfig(new FileOutputStream(configPath));

            System.out.println("... done. Please review the configuration and run this program again.");
            return null;
        }
        else {
            Config.getInstance(new FileInputStream(configPath));
        }
        Config c = Config.getInstance();

        // Read input
        VnfLib vnfLib = VnfLibReader.readFromFile(c.vnfLibFile);
        NetworkGraph ng = TopologyFileReader.readFromFile(c.topologyFile, vnfLib);
        TrafficRequest[] reqs = TrafficRequestsReader.readFromFile(c.requestsFile, ng, vnfLib);

        // Initialize PSA object
        PSA psa = new PSA(ng, vnfLib, reqs, c.s, c.m, c.tmax, c.tmin, c.rho, c.runtime);
        c.createAllEventLoggers().forEach(psa::addEventLogger);

        // Execute actual algorithm
        ParetoFrontier front;
        switch (Config.getInstance().prepMode) {
            case LEAST_CPU:
                front = psa.runPSAPrepCpu();
                break;
            case LEAST_DELAY:
                front = psa.runPSAPrepDelay();
                break;
            case SHORT_PSA:
                front = psa.runPSAPrepPSA();
                break;
            case EXISTING:
                front = psa.runPSAExistingFrontier();
                break;
            default:
                front = psa.runPSARand();
        }

        // Show gui, if required
        if (Config.getInstance().showGui) {
            //OldGuiApp.frontier = front;
            //OldGuiApp.launch(OldGuiApp.class);
            Gui.frontier = front;
            Gui.launch(Gui.class);
        }

        return front;
    }

    /**
     * Initializes a new PSA instance.
     * Note: if you want to execute a simple PSA optimization, execute {@link PSA#runPSA} instead.
     *
     * @param ng      Network graph (problem specific input)
     * @param reqs    All traffic demands (problem specific input)
     * @param s       Number of simultaneously optimized solutions
     * @param m       Number of iterations for every temperature level (may be overriden by runtime)
     * @param tmax    Initial temperature (algorithm runs until {@code T <= Tmin})
     * @param tmin    Final temperature (algorithm runs until {@code T <= Tmin})
     * @param rho     Rate ({@code < 1}) of temperature cooling (algorithm runs until {@code T <= Tmin})
     * @param runtime Optional parameter; sets the algorithms runtime to the given value, in seconds.
     *                If set (> 0), the parameter <code>m</code> will be ignored.
     */
    public PSA(NetworkGraph ng, VnfLib lib, TrafficRequest[] reqs, int s, int m, double tmax, double tmin, double rho, double runtime) {
        this(ng, lib, reqs, s, m, tmax, tmin, rho, runtime, new Random().nextLong());
    }

    /**
     * Initializes a new PSA instance with the given seed.
     * Note: if you want to execute a simple PSA optimization, execute {@link PSA#runPSA} instead.
     *
     * @param ng      Network graph (problem specific input)
     * @param reqs    All traffic demands (problem specific input)
     * @param s       Number of simultaneously optimized solutions
     * @param m       Number of iterations for every temperature level (may be overriden by runtime)
     * @param tmax    Initial temperature (algorithm runs until {@code T <= Tmin})
     * @param tmin    Final temperature (algorithm runs until {@code T <= Tmin})
     * @param rho     Rate ({@code < 1}) of temperature cooling (algorithm runs until {@code T <= Tmin})
     * @param runtime Optional parameter; sets the algorithms runtime to the given value, in seconds.
     *                If set (> 0), the parameter <code>m</code> will be ignored.
     * @param seed    Seed for the Random object.
     */
    public PSA(NetworkGraph ng, VnfLib lib, TrafficRequest[] reqs, int s, int m, double tmax, double tmin, double rho, double runtime, long seed) {
        this.ng = Objects.requireNonNull(ng);
        this.lib = Objects.requireNonNull(lib);
        this.reqs = Objects.requireNonNull(reqs);
        this.pi = new ProblemInstance(ng, lib, reqs, new Objs(lib.getResources()), null);
        this.s = s;
        this.m = m;
        this.tmax = tmax;
        this.tmin = tmin;
        this.rho = rho;
        this.runtime = runtime;

        if (s < 1) throw new IllegalArgumentException("s=" + s);
        if (m < 1) throw new IllegalArgumentException("m=" + m);
        if (tmax <= 0) throw new IllegalArgumentException("tmax=" + tmax);
        if (tmin <= 0) throw new IllegalArgumentException("tmin=" + tmin);
        if (tmax <= tmin) throw new IllegalArgumentException("tmax=" + tmax + ", tmin=" + tmin);
        if (rho >= 1) throw new IllegalArgumentException("rho=" + rho);

        loggers = new LinkedList<>();
        this.seed = seed;
        r = new Random(seed);
    }

    /**
     * Adds a new event logger to this instance.
     * It will be called in certain events during the execution of the algorithm.
     *
     * @param logger Instance of an event logger
     */
    public void addEventLogger(PSAEventLogger logger) {
        loggers.add(Objects.requireNonNull(logger));
    }

    /**
     * Applies (modified) Pareto-Simulated Annealing and attempts to approximate the Pareto Frontier.
     * Starts with an existing placement and attempts to improve it.
     *
     * @return Pareto Frontier of all visited solutions.
     */
    public ParetoFrontier runPSAExistingFrontier() throws InterruptedException, ExecutionException, IOException {
        ParetoFrontier front = IncompleteFlowPlacementReader.readFromCsv(pi, Config.getInstance().existingPlacementFlows);
        Solution[] solutions = front.toArray(new Solution[front.size()]);

        // Sort stuff to match indices
        Arrays.sort(reqs, Comparator.comparing(req -> (req.ingress.name + "," + req.egress.name + "," + Arrays.stream(req.vnfSequence).map(v -> v.name).collect(Collectors.joining(",")))));
        for (Solution s : solutions) {
            Arrays.sort(s.requests, Comparator.comparing(req -> (req.ingress.name + "," + req.egress.name + "," + Arrays.stream(req.vnfSequence).map(v -> v.name).collect(Collectors.joining(",")))));
            Arrays.sort(s.assignments, Comparator.comparing(assig -> (assig.request.ingress.name + "," + assig.request.egress.name + "," + Arrays.stream(assig.request.vnfSequence).map(v -> v.name).collect(Collectors.joining(",")))));
        }

        initialSolutions = Arrays.copyOf(solutions, solutions.length);
        for (Solution s : solutions) {
            s.pi.initialSolutions = initialSolutions;
        }

        // Compute solutions for the remaining traffic requests
        HashSet<HashWrapper> coveredReqs = Arrays.stream(solutions[0].requests).map(HashWrapper::new).collect(Collectors.toCollection(HashSet::new));
        TrafficRequest[] missingReqs = Arrays.stream(reqs).filter(r -> !coveredReqs.contains(new HashWrapper(r))).toArray(TrafficRequest[]::new);
        for (int i = 0; i < solutions.length; i++) {
            solutions[i] = NeighbourSelection.viterbiSelection(missingReqs, solutions[i], Config.getInstance().pNewInstance(tmax, 0), r);
        }

        return runPSA(solutions);
    }

    /**
     * Applies (modified) Pareto-Simulated Annealing and attempts to approximate the Pareto Frontier.
     * Picks random initial solutions.
     *
     * @return Pareto Frontier of all visited solutions.
     */
    public ParetoFrontier runPSARand() throws InterruptedException, ExecutionException {
        // Prepare shortest path pointers:
        ng.getBfsBackpointers();

        // Generate s random solutions:
        if (s == 0) return new ParetoFrontier();
        Solution[] solutions = new Solution[s];
        for (int i = 0; i < s; i++) {
            solutions[i] = NeighbourSelection.randomSelection(reqs, Solution.createEmptyInstance(pi), r);
        }

        return runPSA(solutions);
    }

    /**
     * Applies (modified) Pareto-Simulated Annealing and attempts to approximate the Pareto Frontier.
     * Initial solutions are derived from a shorter, previous PSA execution.
     *
     * @return Pareto Frontier of all visited solutions.
     */
    public ParetoFrontier runPSAPrepPSA() throws InterruptedException, ExecutionException {
        if (s == 0) return new ParetoFrontier();

        PSA preRun = new PSA(ng, lib, reqs, s / 4, m / 4, tmax, tmin, rho * rho, runtime / 4, r.nextLong());
        ArrayList<Solution> start = preRun.runPSARand();

        // Obtain s solutions from the prior Pareto Frontier:
        Solution[] solutions = new Solution[s];
        for (int i = 0; i < s; i++) {
            solutions[i] = start.get(i % start.size());
        }

        return runPSA(solutions);
    }

    /**
     * Prepares the initial solution set by computing a (possibly unfeasible)
     * solution that minimizes individual delays for every traffic request.
     * Executes a regular PSA run with <tt>s</tt> such solutions as starting set.
     *
     * @return pareto frontier of all encountered solutions
     */
    public ParetoFrontier runPSAPrepDelay() throws InterruptedException, ExecutionException {
        HashMap<Node, HashMap<Node, Node.Att>> bp = ng.getDijkstraBackpointers();
        Node[] cpuNodes = ng.getNodes().values().stream().filter(n -> n.resources[0] > 0.0).toArray(Node[]::new);
        Solution[] solutions = new Solution[s];

        for (int k = 0; k < s; k++) {
            TrafficAssignment[] assig = new TrafficAssignment[reqs.length];
            for (int i = 0; i < reqs.length; i++) {
                TrafficRequest req = reqs[i];

                // Find a path with minimum 1 cpu-node and minimal delay:
                Node middle = (req.vnfSequence.length == 0 ? req.ingress : ng.getShortestMiddleStation(req.ingress, req.egress, cpuNodes, bp));

                // Place all VNFs of this chain on 1 random cpu-node of the given path:
                ArrayList<Node> onPath = new ArrayList<>();

                HashMap<Node, Node.Att> fromIngress = bp.get(req.ingress);
                Node.Att c = fromIngress.get(middle);
                do {
                    if (c.node.resources[0] > 0.0) {
                        onPath.add(c.node);
                    }
                    c = (c.pi == null ? null : fromIngress.get(c.pi.getOther(c.node)));
                } while (c != null);

                HashMap<Node, Node.Att> fromMiddle = bp.get(middle);
                c = fromMiddle.get(req.egress);
                while (!c.node.equals(middle)) {
                    if (c.node.resources[0] > 0.0) {
                        onPath.add(c.node);
                    }
                    c = (c.pi == null ? null : fromMiddle.get(c.pi.getOther(c.node)));
                }

                Node[] order = new Node[req.vnfSequence.length];

                if (order.length > 0) {
                    Node pick = onPath.get(r.nextInt(onPath.size()));
                    for (int j = 0; j < order.length; j++) {
                        order[j] = pick;
                    }
                }

                // Create Assignment from given path:
                assig[i] = FlowUtils.fromVnfSequence(req, order, ng, bp);
            }

            solutions[k] = Solution.getInstance(pi, assig);
        }

        return runPSA(solutions);
    }

    /**
     * Prepares the initial solution set by computing a (possibly unfeasible)
     * solution that minimizes cpu utilization.
     * Executes a regular PSA run with <tt>s</tt> such solutions as starting set.
     *
     * @return pareto frontier of all encountered solutions
     */
    public ParetoFrontier runPSAPrepCpu() throws InterruptedException, ExecutionException {
        Solution sol = GreedyCentrality.centrality(ng, lib, reqs);
        Solution[] solutions = new Solution[s];
        Arrays.fill(solutions, sol);

        return runPSA(solutions);
    }

    /**
     * Applies (modified) Pareto-Simulated Annealing and attempts to approximate the Pareto Frontier.
     *
     * @param solutions Initial solution set.
     * @return Pareto Frontier of all visited solutions.
     */
    public ParetoFrontier runPSA(Solution[] solutions) throws InterruptedException, ExecutionException {
        // Only 1 run for each PSA-object is permitted.
        if (executed) {
            throw new IllegalStateException("this PSA instance has already been executed");
        }
        executed = true;

        // Call loggers:
        for (PSAEventLogger logger : loggers) {
            logger.psaStart(ng, reqs, seed);
        }

        final int solutionBatchSize = 1;
        final int s = solutions.length;
        if (s == 0) return new ParetoFrontier();
        int numberOfTemperatureLevels = (int) Math.ceil(Math.log(tmin / tmax) / Math.log(rho));

        // Prepare acceptance probabilities by running one iteration silently:
        if (dominatingNeighboursLastTemp == null) {
            PSA preRun = new PSA(ng, lib, reqs, 1, Math.min(m, 100), tmax, tmin, 0.0, Math.min(runtime, 10.0), r.nextLong());
            preRun.dominatingNeighboursLastTemp = new int[]{Math.min(m/2, 50)};
            preRun.incomparableNeighboursLastTemp = new int[]{Math.min(m/2, 50)};
            preRun.iterationsLastTemp = new int[]{Math.min(m, 100)};
            preRun.runPSA(new Solution[]{solutions[0]});

            dominatingNeighboursLastTemp = new int[s];
            incomparableNeighboursLastTemp = new int[s];
            iterationsLastTemp = new int[s];
            for (int i = 0; i < s; i++) {
                dominatingNeighboursLastTemp[i] = preRun.dominatingNeighboursLastTemp[0];
                incomparableNeighboursLastTemp[i] = preRun.incomparableNeighboursLastTemp[0];
                iterationsLastTemp[i] = preRun.iterationsLastTemp[0];
            }
        }

        // Create initial Pareto Frontier from the given solution set:
        ParetoFrontier paretoFrontier = ParetoFrontier.bruteForce(solutions);
        ParetoFrontier[] paretoFrontiers = new ParetoFrontier[s];
        for (int i = 0; i < s; i++) {
            paretoFrontiers[i] = paretoFrontier.copy();
        }

        // Multithreading!!
        int threads = Runtime.getRuntime().availableProcessors();
        ExecutorService service = Executors.newFixedThreadPool(threads);
        ArrayList<Future<?>> futures = new ArrayList<>((int) Math.ceil((double) s / solutionBatchSize));

        // PSA main loop:
        double t = tmax;
        int iterationNumber = 0;
        long startTime = System.currentTimeMillis();
        while (t > tmin) {
            // Call loggers:
            for (PSAEventLogger logger : loggers) {
                logger.beginTemperatureIteration(t, iterationNumber, paretoFrontier.copy(), solutions);
            }
            long endIteration = (runtime <= 0 ? 0 : startTime + (long) (runtime * 1000.0 * (iterationNumber+1) / numberOfTemperatureLevels));

            // Probabilities to remove and create instances:
            double pReassignVnf = Config.getInstance().pReassignVnf(t, iterationNumber);
            double pNewInstance = Config.getInstance().pNewInstance(t, iterationNumber);

            final int[] acceptedNeighbours = new int[s];
            final int[] totalNumOfNeighbours = new int[1];

            // Draw one neighbor for each solution:
            for (int i = 0; i < s; i = i + solutionBatchSize) {
                int _i = i;
                Random _r = new Random(r.nextLong());
                double _t = t;
                int _iterationNumber = iterationNumber;

                Runnable runnable = () -> {
                    double[] acceptIncomparable = new double[solutionBatchSize];
                    double[] acceptWorse = new double[solutionBatchSize];

                    for (int __i = _i; __i < _i + solutionBatchSize && __i < s; __i++) {
                        // Acceptance probabilities:
                        acceptIncomparable[__i - _i] = Config.getInstance().acceptIncomparable(_t, _iterationNumber, dominatingNeighboursLastTemp[__i], incomparableNeighboursLastTemp[__i], iterationsLastTemp[__i]);
                        acceptWorse[__i - _i] = Config.getInstance().acceptWorse(_t, _iterationNumber, dominatingNeighboursLastTemp[__i], incomparableNeighboursLastTemp[__i], iterationsLastTemp[__i]);

                        dominatingNeighboursLastTemp[__i] = 0;
                        incomparableNeighboursLastTemp[__i] = 0;
                        iterationsLastTemp[__i] = 0;
                    }

                    int tempIter = 0;
                    while ((endIteration > 0 && System.currentTimeMillis() < endIteration)
                            || (endIteration <= 0 && tempIter < m)) {
                        Debugger.println("- New iteration... [T="+_t+",level="+_iterationNumber+",i="+tempIter+"]");

                        for (int __i = _i; __i < _i + solutionBatchSize && __i < s; __i++) {

                            ParetoFrontier _paretoFrontier = paretoFrontiers[__i];

                            // Only relocate a single assignment (false)
                            // or all assignments of a selected VNF (true)?
                            boolean reassignVnf = (_r.nextDouble() <= pReassignVnf);

                            Solution neigh;
                            // Single assignment:
                            if (!reassignVnf) {
                                neigh = NeighbourSelection.replaceTrafficAssignment(solutions[__i], pNewInstance, _r);
                            }
                            // Relocate all assignments of a selected VNF:
                            else {
                                neigh = NeighbourSelection.replaceVnfInstance(solutions[__i], pNewInstance, _r);
                            }
                            if (solutions[__i].assignments.length != neigh.assignments.length) {
                                throw new RuntimeException("Neighbour has "+neigh.assignments.length+" assignments (!= "+solutions[__i].assignments.length+")");
                            }

                            int dominance = ParetoFrontier.getDominance(solutions[__i].getObjectiveVector(), neigh.getObjectiveVector());
                            if (dominance == 0) incomparableNeighboursLastTemp[__i]++;
                            else if (dominance == +1) dominatingNeighboursLastTemp[__i]++;
                            iterationsLastTemp[__i]++;

                            // Update Pareto Frontier, if necessary:
                            if (ParetoFrontier.getDominance(solutions[__i].getObjectiveVector(), neigh.getObjectiveVector()) != -1) {
                                neigh.creationTemperature = _t;
                                neigh.creationIteration = _iterationNumber;
                                int sizeBeforeUpdate = _paretoFrontier.size();

                                ArrayList<Solution> removed = _paretoFrontier.updateParetoFrontier(neigh);

                                // Call loggers:
                                if (!removed.isEmpty() || _paretoFrontier.size() != sizeBeforeUpdate) {
                                    for (PSAEventLogger logger : loggers) {
                                        logger.newSolutionInParetoFrontier(_t, _iterationNumber, neigh);
                                    }
                                }
                            }

                            // Potentially accept neighbor 'neigh':
                            double draw = r.nextDouble();
                            if (draw <= acceptanceProbabilityDynamic(solutions[__i], neigh, acceptIncomparable[__i - _i], acceptWorse[__i - _i])) {
                                solutions[__i] = neigh;
                                acceptedNeighbours[__i]++;


                                double sumLoads = 0.0;
                                double sumCaps = 0.0;
                                for (NodeOverview nodeOv : neigh.nodeMap.values()) {
                                    for (VnfInstances vnfInst : nodeOv.getVnfInstances().values()) {
                                        sumLoads += Arrays.stream(vnfInst.loads).sum();
                                        sumCaps += vnfInst.type.processingCapacity * vnfInst.loads.length;
                                    }
                                }
                                Debugger.println("  - Accepting new solution with load " + (sumLoads / sumCaps) + ": " + neigh.toString());


                            }
                            else {


                                Debugger.println("  - (Solution not accepted.)");


                            }

                            // Call loggers:
                            for (PSAEventLogger logger : loggers) {
                                logger.innerIteration(_t, _iterationNumber, __i, solutions[__i]);
                            }
                        }
                        tempIter++;
                    }
                    for (int __i = _i; __i < _i + solutionBatchSize && __i < s; __i++) {
                        totalNumOfNeighbours[0] += iterationsLastTemp[__i];
                    }
                };
                futures.add(service.submit(runnable));
            }

            // Wait for all threads:
            for (Future<?> future : futures) {
                future.get();
            }
            futures.clear();

            // Combine all Pareto Frontiers:
            for (ParetoFrontier _front : paretoFrontiers) {
                _front.forEach(paretoFrontier::updateParetoFrontier);
            }
            for (int i = 0; i < s; i++) {
                paretoFrontiers[i] = paretoFrontier.copy();
            }

            // Call loggers:
            double acceptanceRatio = (double) Arrays.stream(acceptedNeighbours).sum() / (totalNumOfNeighbours[0]);
            for (PSAEventLogger logger : loggers) {
                logger.endTemperatureIteration(t, iterationNumber, paretoFrontier.copy(), solutions,
                        "visited=" + totalNumOfNeighbours[0],
                        "pReassignVnf=" + pReassignVnf,
                        "acceptanceRatio=" + acceptanceRatio);
            }

            t = t * rho;
            iterationNumber++;
        }
        service.shutdown();

        // Collect all Pareto-optimal points:
        for (ParetoFrontier _front : paretoFrontiers) {
            _front.forEach(paretoFrontier::updateParetoFrontier);
        }

        // Call loggers:
        for (PSAEventLogger logger : loggers) {
            logger.psaEnd(paretoFrontier);
        }

        return paretoFrontier;
    }

    /**
     * Returns acceptance probabilities based on the dominance relationship of the given points.
     *
     * @param x                  Original solution.
     * @param y                  Neighbor that may potentially be accepted (in the place of x).
     * @param acceptIncomparable Desired acceptance probability if x and y are incomparable.
     * @param acceptWorse        Desired acceptance probability if x is dominated by y
     * @return <tt>1.0</tt>: if x is dominated by y |
     * <tt>acceptIncomparable</tt>: if x and y are incomparable |
     * <tt>acceptWorse</tt>: if y is dominated by x
     */
    private static double acceptanceProbabilityDynamic(Solution x, Solution y, double acceptIncomparable, double acceptWorse) {
        if (!x.isFeasible() && y.isFeasible()) return 1.0;
        if (x.isFeasible() && !y.isFeasible()) return acceptWorse;

        if (!x.isFeasible() && !y.isFeasible()) {
            int dominance = ParetoFrontier.getDominance(x.getUnfeasibleVector(), y.getUnfeasibleVector());
            if (dominance == +1) return 1.0;
            if (dominance == 0) return acceptIncomparable;
            return acceptWorse;
        }

        int dominance = ParetoFrontier.getDominance(x.getObjectiveVector(), y.getObjectiveVector());
        if (dominance == +1) return 1.0;
        if (dominance == 0) return acceptIncomparable;
        return acceptWorse;
    }
}
