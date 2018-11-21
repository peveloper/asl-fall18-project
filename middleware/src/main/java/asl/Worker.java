package main.java.asl;

import org.apache.logging.log4j.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import static main.java.asl.MW.logger;

class Worker implements Runnable {

    private final BlockingQueue queue;
    private final Integer nMcAddresses;
    private final List<String> mcAddresses;
    private final Boolean readSharded;
    private final ByteArrayOutputStream stream;
    private Integer ID;
    private ByteBuffer responseBuffer;
    private Map<String, SelectionKey> mcSocketChannelKeys;
    private Request pendingRequest;
    private ByteArrayOutputStream responseStream;
    private Map<SelectionKey, ByteBuffer> multiRequest;
    private Map<Long, Statistic> recordedEvents;
    private Integer nResponses;
    private Selector selector;

    /**
     * @param queue Shared {@link MW#queue queue} of {@link Request}
     * @param ID {@link Worker} Identifier
     * @param mcAddresses {@link RunMW#mcAddresses}
     * @param readSharded {@link RunMW#readSharded}
     */
    Worker(BlockingQueue queue, int ID, List<String> mcAddresses, boolean readSharded) {
        this.queue = queue;
        this.ID = ID;
        this.mcAddresses = mcAddresses;
        this.nMcAddresses = mcAddresses.size();
        this.readSharded = readSharded;
        this.mcSocketChannelKeys = new HashMap<>();
        this.stream = new ByteArrayOutputStream();
        this.responseStream = new ByteArrayOutputStream();
        this.multiRequest = new HashMap<>();
        this.recordedEvents = new ConcurrentHashMap<>();
        this.nResponses = 0;

        List<SocketChannel> mcServers = new ArrayList<>();

        try {
            this.selector = Selector.open();

            for (String mcAddress : mcAddresses) {
                String[] address = mcAddress.split(":");
                InetSocketAddress inetSocketAddress = new InetSocketAddress(address[0], Integer.parseInt(address[1]));
                SocketChannel socketChannel = SocketChannel.open(inetSocketAddress);
                mcServers.add(socketChannel);
                socketChannel.configureBlocking(false);
                SelectionKey selectionKey = socketChannel.register(selector, SelectionKey.OP_WRITE, mcAddress);
                mcSocketChannelKeys.put(mcAddress, selectionKey);
            }

            List<SocketChannel> notFinished = new ArrayList<>(mcServers);
            while (notFinished.size() > 0) {
                List<SocketChannel> toRemove = new ArrayList<>();
                for (SocketChannel socketChannel : notFinished) {
                    if (socketChannel.finishConnect()) {
                        toRemove.add(socketChannel);
                    }
                }
                notFinished.removeAll(toRemove);
            }

        } catch (Exception ex) {
            logger.error("WORKER %d: Object constructor.", ID, ex);
            System.exit(0);
        }
    }

    /**
     * @param response String containing the response returned by memcached instances
     * @return Array of keys of the response
     */
    private String getKeyFromResponse(String response){
        String[] keys = response.split(" ");
        return keys[1];
    }

    /**
     * @return The map containing all {@link Statistic} collected by the {@link Worker}
     */
    Map<Long, Statistic> getStatistics(){
        return recordedEvents;
    }

    /**
     * Orders a MULTI-GET request as received by memtier client
     */
    private void orderResponse(){
        String[] splittedResponse = responseStream.toString().split("(?=VALUE)");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        String[] orderedKeys =  pendingRequest.getKeys();

        for (String orderedKey : orderedKeys) {
            for (String response : splittedResponse) {
                if (response.charAt(0) != 'E') {
                    if (orderedKey.equals(getKeyFromResponse(response))) {
                        try {
                            outputStream.write(response.getBytes());
                            break;
                        } catch (IOException e) {
                            logger.error("WORKER %d: Failed to order MULTI-GET responses.", ID);
                            System.exit(0);
                        }
                    }
                }
            }
        }
        String end = "END\r\n";
        try {
            outputStream.write(end.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
        responseStream.reset();
        responseStream = outputStream;
    }

    /**
     * @return true or false if the {@link Request} has been all read.
     */
    private boolean stopReading(){

        if (stream.size() > 3){

            String endMessage = "END\r\n";
            String storedMessage = "STORED\r\n";

            int positionCounterString = 0;
            int indexEnd = stream.size() - endMessage.length();
            int indexStored = stream.size() - storedMessage.length();

            while((stream.toByteArray()[indexEnd]) == endMessage.charAt(positionCounterString)){
                indexEnd++;
                positionCounterString++;
                if (indexEnd >= stream.size()){
                    break;
                }
            }

            if (indexEnd == stream.size()){
                return true;
            }
            positionCounterString = 0;

            while((stream.toByteArray()[indexStored]) == storedMessage.charAt(positionCounterString)){
                indexStored++;
                positionCounterString++;
                if (indexStored >= stream.size()){
                    break;
                }
            }

            return indexStored == stream.size();

        }
        return  false;

    }

    /**
     * @param selectionKey {@link SelectionKey} interested in READ
     *
     * Gets the corresponding {@link SocketChannel}.
     * Reads bytes from the {@link SocketChannel} and store them into
     * an {@link ByteArrayOutputStream}.
     * Collects the {@link #nResponses nResponses} and checks if it is
     * equal to {@link #multiRequest multiRequest.size()}.
     * If so, it stores all required {@link Statistic} and answers back
     * to the corresponding {@link SocketChannel}.
     * Then it sets the interest for all {@link SelectionKey} to WRITE.
     */
    private void read(SelectionKey selectionKey) {
        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();
        ByteBuffer buffer = ByteBuffer.allocate(MW.BUFFER_SIZE);

        int bytesRead = 0;
        try {

            while (!stopReading() && (bytesRead = socketChannel.read(buffer)) >=0 ) {
                stream.write(Arrays.copyOfRange(buffer.array(), 0, bytesRead));
                buffer.clear();
            }
        } catch (IOException ex) {
            logger.error("WORKER %d: Response read", ID, ex);
            System.exit(0);
        }

        if (bytesRead == -1) {
            try {
                socketChannel.close();
                selectionKey.cancel();
                return;
            } catch (IOException e) {
                logger.error("WORKER %d: Cannot shutdown Socket Channel", ID);
                System.exit(0);
            }
        }

        nResponses++;


        buffer = ByteBuffer.wrap(stream.toByteArray());

//        MW.logger.log(Level.getLevel("INFO"), new String(buffer.array()));

        if(pendingRequest.getType() == 2) {
            // SKIP END\r\n
            int offset = 5;
            if(buffer.get(0) == 'E') {
                pendingRequest.increaseMisses();
            }else{
                try {
                    responseStream.write(Arrays.copyOfRange(buffer.array(), 0, buffer.capacity() - offset));
                } catch (IOException e) {
                    logger.error("WORKER %d: Cannot write MULTI-GET response to stream.");
                    System.exit(0);
                }
            }
        }

        if(nResponses == multiRequest.size()) {
            pendingRequest.setIncomingServiceTime();
            if(pendingRequest.getType() == 0 || pendingRequest.getType() == 1) {
                //SET / GET REPLY
                if (pendingRequest.getType() == 1) {
                    // GET MISS
                    if(buffer.get(0) == 'E')
                        pendingRequest.increaseMisses();
                }
                responseBuffer = ByteBuffer.wrap(buffer.array());
            }else if(buffer.get(0) == 'E'){
                //MULTI-GET COMPLETE ALL MISSES
                if(responseBuffer == null){
                    responseBuffer = ByteBuffer.allocate(buffer.capacity());
                    responseBuffer = ByteBuffer.wrap(buffer.array());
                }
            }else if(buffer.get(0) == 'V'){
                orderResponse();
                responseBuffer = ByteBuffer.wrap(responseStream.toByteArray());
            }

            pendingRequest.answer(responseBuffer);

            recordedEvents.put(System.nanoTime(), new Statistic(pendingRequest.getType(),
                    pendingRequest.getQueueWaitingTime(), pendingRequest.getServiceTime(),
                    pendingRequest.getResponseTime(), queue.size(), pendingRequest.getMisses(),
                    pendingRequest.getSize()));

            MW.endTime = System.nanoTime();
            pendingRequest = null;
            responseBuffer = null;
            responseStream.reset();
            multiRequest.clear();
            nResponses = 0;


            selector.keys().forEach(key -> key.interestOps(SelectionKey.OP_WRITE));
        }
        stream.reset();
    }

    /**
     * @param selectionKey {@link SelectionKey} interested in WRITE.g
     *
     * It loads a new {@link Request} if {@link #pendingRequest pendingRequest}
     * is null.
     * Takes the {@link Request} corresponding to the {@link SelectionKey}
     * and writes its bytes to the memcached instance.
     * Then it sets the interest for all {@link SelectionKey} to READ.
     */
    private void write(SelectionKey selectionKey){

        if(pendingRequest == null){
            loadRequest();
        }

        SocketChannel socketChannel = (SocketChannel) selectionKey.channel();

        ByteBuffer writeBuffer = multiRequest.get(selectionKey);
        if(writeBuffer == null){
            return;
        }
//        MW.logger.log(Level.getLevel("INFO"), "SENDING TO MC: " + new String(writeBuffer.array()));
        writeBuffer.rewind();
        try {
            socketChannel.write(writeBuffer);
        } catch (IOException e) {
            logger.error("WORKER %d: Failed to send request to memcached server.");
            System.exit(0);
        }

        selectionKey.interestOps(SelectionKey.OP_READ);
    }

    /**
     * @param key {@link Request} key
     * @return the index of the memcached target
     */
    private int getServerFromKey(String key){
        int i = key.hashCode() % nMcAddresses;
        i += (i < 0) ? nMcAddresses : 0;
        return i;
    }

    /**
     * Dequeues a {@link Request}, based on the {@link Request#type} it stores
     * {@link Request#getKeys()} in {@link #multiRequest multiRequest}.
     */
    private void loadRequest(){
        Request request = null;
        try {
            request = (Request) queue.take();
            request.setDequeueWaitingTime();
        } catch (InterruptedException e) {
            logger.error("WORKER %d: Error during request dequeue.");
            System.exit(0);
        }

        if(request.getType() == 2 && readSharded){
            String[] keys = request.getKeys();
            for(int i=0; i < nMcAddresses; i++) {
                if(keys.length <= nMcAddresses) {
                    if (i < keys.length) {
                        SelectionKey selectionKey = mcSocketChannelKeys.get(mcAddresses.get(i));
                        ByteBuffer buffer;
                        StringBuilder req = new StringBuilder("get");

                        req.append(" ").append(keys[i]);
                        req.append("\r\n");

                        buffer = ByteBuffer.wrap(req.toString().getBytes());
                        multiRequest.put(selectionKey, buffer);
                    }
                } else {

                    SelectionKey selectionKey = mcSocketChannelKeys.get(mcAddresses.get(i % nMcAddresses));
                    ByteBuffer buffer;
                    StringBuilder req = new StringBuilder("get");


                    for(int j=i; j<keys.length; j+=nMcAddresses) {
                        req.append(" ").append(keys[j]);
                    }

                    req.append("\r\n");

                    buffer = ByteBuffer.wrap(req.toString().getBytes());
                    multiRequest.put(selectionKey, buffer);
                }
            }
        }else if(request.getType() == 1 || (request.getType() == 2 && !readSharded)){
            SelectionKey selectionKey = mcSocketChannelKeys.get(mcAddresses.get(getServerFromKey(request.getKeys()[0])));
            multiRequest.put(selectionKey, request.getBuffer());
        }else{
            for(SelectionKey selectionKey: selector.keys()){
                multiRequest.put(selectionKey, request.getBuffer());
            }
        }
        pendingRequest = request;
        pendingRequest.setOutgoingServiceTime();
    }

    /**
     * In the infinite loop it keeps waiting for a ready-to-use {@link SelectionKey}.
     * If the interest is set to WRITE, then it calls {@link #write(SelectionKey)
     * write(SelectionKey)}.
     * If the interest is set to READ, it calls {@link #read(SelectionKey)
     * read(SelectionKey)}.
     */
    @Override
    public void run() {
        try {
            MW.gate.await();
            while (true) {

                selector.select();
                Set<SelectionKey> readyKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = readyKeys.iterator();

                while (iterator.hasNext()) {
                    final SelectionKey selectionKey = iterator.next();
                    iterator.remove();

                    if (selectionKey.isValid() && selectionKey.isReadable()) {
                        read(selectionKey);
                    } else if(selectionKey.isValid() && selectionKey.isWritable()) {
                        write(selectionKey);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}