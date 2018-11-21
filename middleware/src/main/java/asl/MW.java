package main.java.asl;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


class MW {

    private String myIp;
    private Integer port;
    private BlockingQueue<Request> queue;
    private Boolean started = false;
    private final ByteBuffer buffer;
    private final ByteArrayOutputStream stream;
    static final Integer BUFFER_SIZE = 2048;
    static ArrayList<Worker> workerThreads;
    static Long startTime, endTime;
    static Logger logger;
    static CyclicBarrier gate;


    /**
     * @param myIp Address to listen to
     * @param port Port to listen to
     * @param mcAddresses List of IP addresses of memcached instances
     * @param numThreadsPTP Number of worker threads in the pool
     * @param readSharded Flag multiget sharded/non-sharded
     *
     * Configures {@link #logger logger} to later log statistics and/or errors.
     * Creates a thread-pool and instantiates numThreadsPTP Workers
     */

    MW(String myIp, int port, List<String> mcAddresses, int numThreadsPTP, boolean readSharded){
        this.myIp = myIp;
        this.port = port;
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
        this.stream = new ByteArrayOutputStream();
        this.queue = new LinkedBlockingQueue();
        workerThreads = new ArrayList<>();
        gate = new CyclicBarrier(numThreadsPTP + 1);

        setupLog4J();
        logger = LogManager.getLogger();

        ExecutorService executorService = Executors.newFixedThreadPool(numThreadsPTP);
        for(int i=0; i< numThreadsPTP; i++){
            Worker workerThread = new Worker(queue, i, mcAddresses, readSharded);
            executorService.execute(workerThread);
            workerThreads.add(workerThread);
        }
        try {
            gate.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            logger.log(Level.getLevel("INFO"), "Failed to launch threads");
            System.exit(0);
        }
    }


    /**
     * Loads the log4j properties file
     */
    private static void setupLog4J(){
        System.setProperty("log4j.configuration", new File("log4j2.xml").toURI().toString());
    }


    /**
     * @param request Request to handle
     * @throws InterruptedException
     *
     * Enqueues request into {@link #queue queue}, sets {@link #startTime startTime}
     * and changes the selectionKey interest to WRITE.
     */
    private void enqueueRequest(Request request) throws InterruptedException {
        SelectionKey selectionKey = request.getSelectionKey();
        selectionKey.interestOps(SelectionKey.OP_WRITE);
        if(!started){
            started = true;
            startTime = System.nanoTime();
        }

        request.setEnqueueWaitingTime();
        queue.put(request);
    }

    /**
     * @return true or false whether the request has been finished/not
     */
    private boolean stopReading(){

        if (stream.size() > 3){

            String endMessage = "\r\n";
//            String storedMessage = "STORED\r\n";

            int positionCounterString = 0;
            int indexEnd = stream.size() - endMessage.length();
//            int indexStored = stream.size() - storedMessage.length();

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

//            while((stream.toByteArray()[indexStored]) == storedMessage.charAt(positionCounterString)){
//                indexStored++;
//                positionCounterString++;
//                if (indexStored >= stream.size()){
//                    break;
//                }
//            }
//
//            return indexStored == stream.size();

        }
        return  false;

    }

    /**
     * Opens a ServerSocketChannel and starts listening on {@link #myIp myIp}, {@link #port port}.
     * In the infinite loop keeps waiting for a ready-to-use SelectionKey.
     * Either accepts an incoming connection or read from the SocketChannel.
     * If the SelectionKey interest is set to ACCEPT, accepts the connection and sets the
     * SelectionKey interest to READ.
     * If the Selection key is set to READ, then read the bytes from the SocketChannel into
     * {@link #stream stream}, store the (partial) content into {@link #stream stream}.
     * When request is completed, sets the incoming response time,
     * calls {@link #enqueueRequest(Request) enqueueRequest}.
     */
    void run(){
        Runtime.getRuntime().addShutdownHook(new Writer());
        logger.info(String.format("START: Middleware listening on address %s:%d.", myIp, port));

        try {
            Selector selector = Selector.open();
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            InetSocketAddress inetSocketAddress = new InetSocketAddress(myIp, port);
            serverSocketChannel.bind(inetSocketAddress);
            serverSocketChannel.configureBlocking(false);

            int ops = serverSocketChannel.validOps();
            serverSocketChannel.register(selector, ops, null);

            while(true){
                selector.select();
                Set<SelectionKey> readyKeys = selector.selectedKeys();
                Iterator iterator = readyKeys.iterator();

                while(iterator.hasNext()){

                    long responseTime = System.nanoTime();
                    SelectionKey selectionKey = (SelectionKey) iterator.next();

                    SocketChannel socketChannel;
                    if(selectionKey.isValid() && selectionKey.isAcceptable()){
                        socketChannel = serverSocketChannel.accept();
                        socketChannel.configureBlocking(false);
                        socketChannel.register(selector, SelectionKey.OP_READ);

                    }else if (selectionKey.isValid() && selectionKey.isReadable()){
                        socketChannel = (SocketChannel) selectionKey.channel();
                        int bytesRead = 0;

                        while (!stopReading() && (bytesRead = socketChannel.read(buffer)) >=0 ) {
                            stream.write(Arrays.copyOfRange(buffer.array(), 0, bytesRead));
                            buffer.clear();
                        }

                        if(bytesRead == -1){
                            socketChannel.close();
                            continue;
                        }

                        Request request = new Request(selectionKey, ByteBuffer.wrap(stream.toByteArray()), responseTime);
                        stream.reset();

                        try {
                            enqueueRequest(request);
                        } catch (InterruptedException e) {
                            logger.error("Failed to enqueue request");
                            System.exit(0);
                        }
                    }
                    iterator.remove();
                }
            }
        } catch (Exception e) {
            logger.error("Unknown Exception.", e);
            System.exit(0);
        }
    }

}