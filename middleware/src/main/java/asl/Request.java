package main.java.asl;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;

public class Request {

    private ByteBuffer buffer;
    private SelectionKey selectionKey;
    private Integer type, nMisses;
    private Long enqueueWaitingTime, dequeueWaitingTime, outgoingServiceTime, incomingServiceTime, incomingResponseTime;

    /**
     * @param selectionKey key to respond to
     * @param buffer keeps the bytes of the request
     *
     * Sets the {@link #incomingResponseTime incomingResponseTime}
     */
    Request(SelectionKey selectionKey, ByteBuffer buffer, long incomingResponseTime){
        this.selectionKey = selectionKey;
        this.buffer = buffer;
        this.type = -1;
        this.enqueueWaitingTime = 0L;
        this.dequeueWaitingTime = 0L;
        this.nMisses = 0;
        this.incomingResponseTime = incomingResponseTime;
    }

    /**
     * @return the string containing the the bytes of the request
     */
    @Override
    public String toString(){
        return new String(buffer.array());
    }

    /**
     * @return the array of keys in the request buffer
     */
    String[] getKeys() {
        String request = this.toString();
        String[] keys = request.split(" |\r\n");
        return Arrays.copyOfRange(keys, 1, keys.length);
    }

    /**
     * @return the request's type: 0 (SET), 1 (GET), 2 (MULTI-GET)
     */
    int getType() {
        buffer.rewind();
        type = ((char) buffer.get(0) == 's') ? 0 : 1;
        type = (type == 1 && this.getKeys().length > 1) ? type = 2 : type;
        return type;
    }

    /**
     * @return the socketChannel corresponding to the {@link #selectionKey selectionKey}
     */
    private SocketChannel getSocketChannel(){
        return (SocketChannel) this.getSelectionKey().channel();
    }

    /**
     * @return the {@link #selectionKey selectionKey}
     */
    SelectionKey getSelectionKey() {
        return selectionKey;
    }

    /**
     * @return the number of keys in the request
     */
    int getSize() {
        return getKeys().length;
    }

    /**
     * @return the buffer containing the request's bytes
     */
    ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * @param responseBuffer buffer containing the response
     *
     * Answers back to the corresponding socketChannel
     */
    boolean answer(ByteBuffer responseBuffer) {
        responseBuffer.rewind();
        try {
//            MW.logger.log(Level.getLevel("INFO"), "ANSWERING: " + new String(responseBuffer.array()));
            this.getSocketChannel().write(responseBuffer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.selectionKey.interestOps(SelectionKey.OP_READ);
        return true;
    }

    /**
     * Increases the number of misses for this request (only if GET or MULTI-GET)
     */
    void increaseMisses() {
        nMisses++;
    }

    /**
     * @return the number of misses
     */
    int getMisses() {
        return nMisses;
    }


    /**
     * Sets the enqueue time
     */
    void setEnqueueWaitingTime(){
        this.enqueueWaitingTime = System.nanoTime();
    }

    /**
     * Sets the dequeue waiting time
     */
    void setDequeueWaitingTime(){
        this.dequeueWaitingTime = System.nanoTime();
    }

    /**
     * @return the response time
     */
    long getResponseTime(){
        return System.nanoTime() - incomingResponseTime;

    }

    /**
     * @return the queue waiting time
     */
    long getQueueWaitingTime(){
        return dequeueWaitingTime - enqueueWaitingTime;
    }

    /**
     * Stops the service time
     */
    void setOutgoingServiceTime(){
        this.outgoingServiceTime = System.nanoTime();
    }

    /**
     *  Sets the service time
     */
    void setIncomingServiceTime(){
        this.incomingServiceTime = System.nanoTime();
    }

    /**
     *
     * @return the serviceTime
     */
    long getServiceTime() {
        return incomingServiceTime - outgoingServiceTime;
    }

}