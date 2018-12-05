package main.java.asl;

class Statistic {

    private Long queueWaitingTime, serviceTime, responseTime;
    private Integer type, queueSize, misses, size;

    /**
     * @param type {@link Request#type Request type}
     * @param queueWaitingTime {@link Request#getQueueWaitingTime() Request queue waiting time}
     * @param serviceTime {@link Request#getServiceTime() Request service time}
     * @param responseTime
     * @param queueSize {@link MW#queue MW queue size}
     * @param misses {@link Request#nMisses Request number of misses}
     * @param size {@link Request#getSize() Request keys size}
     */
    Statistic(int type, long queueWaitingTime, long serviceTime, long responseTime, int queueSize, int misses, int size){
        this.type = type;
        this.queueWaitingTime = queueWaitingTime;
        this.serviceTime = serviceTime;
        this.responseTime = responseTime;
        this.queueSize = queueSize;
        this.misses = misses;
        this.size = size;
    }

    /**
     * @return {@link #queueSize queueSize}
     */
    int getQueueSize() {
        return queueSize;
    }

    /**
     * @return {@link #type type}
     */
    int getType() {
        return type;
    }

    /**
     * @return {@link #queueWaitingTime queueWaitingTime}
     */
    long getQueueWaitingTime() {
        return queueWaitingTime;
    }

    /**
     * @return {@link #serviceTime serviceTime}
     */
    long getServiceTime() {
        return serviceTime;
    }

    /**
     * @return {@link #responseTime responseTime}
     */
    long getResponseTime() { return responseTime;}

    /**
     * @return {@link #misses misses}
     */
    int getMisses() { return misses; }

    /**
     * @return {@link #size size}
     */
    int getSize() {
        return size;
    }

}
