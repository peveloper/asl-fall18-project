package test.java.asl;

import java.util.UUID;

public class LoadBalancer {
    private static Integer nServers = 10;
    private static Integer nStrings = 1000000;

    public static void main(String args[]){
        int[] occurrences = new int[nServers];
        for(int i=0; i<nServers; i++) {
            occurrences[i]= 0;
        }

        for(int i=0; i<nStrings; i++) {
            String random = UUID.randomUUID().toString();
            occurrences[getServerFromKey(random)] += 1;
        }

        for(int i=0; i<occurrences.length; i++) {
            System.out.println(String.format("Server %d got %d jobs.", i, occurrences[i]));
        }
    }

    private static int getServerFromKey(String key){
        int i = key.hashCode() % nServers;
        i += (i < 0) ? nServers : 0;
        return i;
    }
}
