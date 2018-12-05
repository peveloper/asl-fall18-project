package main.java.asl;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.LoggerContext;
import java.util.*;

import static java.lang.Math.round;
import static java.lang.Math.ulp;

class Writer extends Thread {

    private Map<Long, Statistic> statistics;
    private Long startTime, endTime;
    private final Long step = 5000000000L;

    /**
     * Initialize the map containing all {@link Worker} {@link Statistic}.
     */
    Writer(){
        this.statistics = new HashMap<>();
    }

    /**
     * @param seconds elapsed time in seconds
     * @return a String containing the statistics to be written to the log file.
     */
    private String aggregateStatistics(double seconds){
        double avgThroughput;
        double avgQueueSize, nSet, nGet, nMultiGets, avgMultiGetSize;
        double avgQueueWaitingTime, avgServiceTime, avgResponseTime, avgSetResponseTime, avgGetResponseTime,
                avgMultiGetResponseTime, getMisses, multiGetMisses;

        avgThroughput = avgQueueSize = nSet = nGet = nMultiGets = avgMultiGetSize = 0;
        avgQueueWaitingTime = avgServiceTime = avgResponseTime = avgSetResponseTime = avgGetResponseTime = 0;
        avgMultiGetResponseTime = getMisses = multiGetMisses = 0;

        ArrayList<Long> toRemove = new ArrayList<>();


        double avg_rt = 0.0;
        for(Long stat : statistics.keySet()){
            avg_rt +=  statistics.get(stat).getResponseTime();
            assert(stat < endTime && stat > MW.startTime);
            long win = startTime + step;
            if (stat <= win){
                Statistic statistic = statistics.get(stat);
                switch(statistic.getType()){
                    case 0:
                        nSet ++;
                        avgSetResponseTime += (double) statistic.getResponseTime() / 1e6;
                        break;
                    case 1:
                        nGet ++;
                        avgGetResponseTime += (double) statistic.getResponseTime() / 1e6;
                        getMisses += statistic.getMisses();
                        break;
                    case 2:
                        nMultiGets ++;
                        avgMultiGetResponseTime += (double) statistic.getResponseTime() / 1e6;
                        multiGetMisses += statistic.getMisses();
                        avgMultiGetSize += statistic.getSize();
                        break;
                }
                avgThroughput ++;
                avgQueueSize += statistic.getQueueSize();
                avgQueueWaitingTime += (double) statistic.getQueueWaitingTime() / 1e6;
                avgServiceTime += (double) statistic.getServiceTime() / 1e6;
                toRemove.add(stat);
            }
        }

        toRemove.forEach(stat -> statistics.remove(stat));

        double totOps = nGet + nSet + nMultiGets;
        System.out.println(avg_rt / totOps / 1000 / 1000 + " msec");
        System.out.println(avg_rt / 1e6 / totOps + " msec");
        int opsType = 0;
        long epsilon = 500000000L;
        double runningTime = (MW.endTime - MW.startTime);
        double winSize = round(((runningTime) < (step + epsilon)) ? ((runningTime) / 1e9) : (step / 1e9));
        System.out.println(winSize);
        double getMissRatio = 0.0, multiGetMissRatio = 0.0;

        if(totOps > 0) {
            startTime += step;
            avgThroughput /= winSize;
            avgQueueSize /= totOps;
            avgQueueWaitingTime /= totOps;
            avgServiceTime /= totOps;

            if (nSet > 0) {
                avgSetResponseTime /= nSet;
                opsType ++;
            }
            if (nGet > 0) {
                avgGetResponseTime /= nGet;
                opsType ++;
                getMissRatio = getMisses / nGet;
            }
            if (nMultiGets > 0) {
                avgMultiGetResponseTime /= nMultiGets;
                opsType ++;
                multiGetMissRatio = multiGetMisses / (double) nMultiGets;
                avgMultiGetSize /= nMultiGets;
            }
            avgResponseTime =  ((avgSetResponseTime + avgGetResponseTime + avgMultiGetResponseTime) / (opsType));
        }

        return String.format("%5.2f, %f, %f, %f, %f, %f, %f, %f, %f, %f, %f, %f, %.2f, %.2f, %f", seconds,
                avgThroughput, avgQueueSize, avgQueueWaitingTime, avgServiceTime, avgResponseTime,
                avgSetResponseTime, avgGetResponseTime, avgMultiGetResponseTime, nSet, nGet, nMultiGets,
                getMissRatio, multiGetMissRatio, avgMultiGetSize);
    }

    /**
     * @param stats copy of original {@link #statistics statistics}
     *
     * It logs the distribution of {@link Request} and {@link Request#incomingResponseTime} every 0.1 ms.
     */
    private void logHistogram(HashMap<Long, Statistic> stats) {
        HashMap<Long, Statistic> getSTATS = new HashMap<>();
        HashMap<Long, Statistic> setSTATS = new HashMap<>();

        double maxGET, maxSET;

        for (long stat: stats.keySet()) {
            if (stats.get(stat).getType() != 0) {
                getSTATS.put(stat, stats.get(stat));
            }
        }

        for (long stat: stats.keySet()) {
            if (stats.get(stat).getType() == 0) {
                setSTATS.put(stat, stats.get(stat));
            }
        }


        MW.logger.log(Level.getLevel("STATS"), "Type, Interval, Responses");


        if (setSTATS.entrySet().size() > 0) {

            maxSET = (double) setSTATS.entrySet().stream().max((entry1, entry2) ->
                    (entry1.getValue().getResponseTime() > entry2.getValue().getResponseTime()) ?
                            1 : -1).get().getValue().getResponseTime() / 1e6;


            int nSETIntervals = (int) (maxSET * 10) + 1;
            int[] SETintervals = new int[nSETIntervals];

            for(long stat: setSTATS.keySet()) {
                if (setSTATS.get(stat).getType() == 0) {
                    int pos = (int) (setSTATS.get(stat).getResponseTime() / 1e5);
                    SETintervals[pos]++;
                }
            }


            for(int i=0; i < nSETIntervals; i++) {
                MW.logger.log(Level.getLevel("STATS"), ("SET, " + (i + 1.0) / 10.0) + ", " + SETintervals[i]);
            }
        }

        if (getSTATS.entrySet().size() > 0) {

            maxGET = (double) getSTATS.entrySet().stream().max((entry1, entry2) ->
                    (entry1.getValue().getResponseTime() > entry2.getValue().getResponseTime()) ?
                            1 : -1).get().getValue().getResponseTime() / 1e6;

            int nGETIntervals = (int) (maxGET * 10) + 1;



            int[] GETintervals = new int[nGETIntervals];



            for(long stat: getSTATS.keySet()) {
                if (getSTATS.get(stat).getType() != 0) {
                    int pos = (int) (getSTATS.get(stat).getResponseTime() / 1e5);
                    GETintervals[pos]++;
                }
            }


            for(int i=0; i < nGETIntervals; i++) {
                MW.logger.log(Level.getLevel("STATS"), ("GET, " + (i + 1.0) / 10.0) + ", " + GETintervals[i]);
            }
        }
    }

    /**
     * Calls {@link #aggregateStatistics(double)} and {@link #logHistogram(HashMap)}.
     * Then shutdowns the logger.
     */
    @Override
    public void run(){
        if(MW.endTime == null) {
            startTime = System.nanoTime();
            endTime = System.nanoTime();
        } else {
            startTime = MW.startTime;
            endTime = MW.endTime;
        }

        double runningTime = (endTime - startTime) / 1e9;
        System.out.println(runningTime);
        double seconds = (step / 1e9);

        for(Worker worker: MW.workerThreads){
            for(Long stat : worker.getStatistics().keySet()){
                statistics.put(stat, worker.getStatistics().get(stat));
            }
        }

        HashMap<Long, Statistic> statisticsCopy = new HashMap<>(statistics);

        while(!statistics.isEmpty()){
            MW.logger.log(Level.getLevel("STATS"), aggregateStatistics(seconds));
            if(seconds + (step / 1e9) < runningTime) {
                seconds += 5;
            }
        }

        logHistogram(statisticsCopy);

        MW.logger.info("END: Middleware stopped.");

        //shutdown log4j2
        if( LogManager.getContext() instanceof LoggerContext ) {
            Configurator.shutdown((LoggerContext)LogManager.getContext());
            LogManager.shutdown();
        }

    }
}
