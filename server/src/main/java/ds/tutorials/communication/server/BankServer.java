package ds.tutorials.communication.server;

import ds.tutorials.sycnhronization.DistributedLock;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class BankServer {
    private int serverPort;
    private DistributedLock leaderLock;

    private AtomicBoolean isLeader = new AtomicBoolean(false);
    private byte[] leaderConnectivityInfo;

    private Map<String, Double> accounts = new HashMap<>();

    public BankServer(String host, int port) throws IOException, InterruptedException, KeeperException {
        this.serverPort = port;
        leaderLock = new DistributedLock("BankServerTestCluster", buildConnectivityInfor(host, port));
    }

    public void setAccountBalance(String accountId, double value) {
        accounts.put(accountId, value);
    }

    public double getAccountBalance(String accountId) {
        Double value = accounts.get(accountId);
        return (value != null) ? value : 0.0;
    }

    public boolean isLeader() {
        return isLeader.get();
    }

    private synchronized void setCurrentLeaderData(byte[] leaderConnectivityInfo) {
        this.leaderConnectivityInfo = leaderConnectivityInfo;
    }

    public void startServer() throws IOException, InterruptedException, KeeperException {
        Server server = ServerBuilder
                .forPort(serverPort)
                .addService(new CheckBalanceServiceImpl(this))
                .addService(new SetBalanceServiceImpl(this))
                .build();
        server.start();
        System.out.println("BankServer Started and ready to accept requests on port " + serverPort);

        tryToBeLeader();
        server.awaitTermination();
    }

    public static String buildConnectivityInfor(String IP, int port) {
        StringBuilder builder = new StringBuilder();
        builder.append(IP).append(":").append(port);
        return builder.toString();
    }

    public static void main(String[] args) throws Exception {
        DistributedLock.setZooKeeperURL("localhost:2181");
        if (args.length != 1) {
            System.out.println("Usage executable-name <port>");
        }

        int serverPort = Integer.parseInt(args[0]);

        BankServer server = new BankServer("localhost", serverPort);
        server.startServer();
    }

    public synchronized String[] getCurrentLeaderConnectivityInfo() {
        return new String(leaderConnectivityInfo).split(":");
    }

    public List<String[]> getOthersConnectivityInfo() throws KeeperException, InterruptedException {
        List<String[]> result = new ArrayList<>();
        List<byte[]> othersData = leaderLock.getOthersData();
        for (byte[] data : othersData) {
            String[] dataStrings = new String(data).split(":");
            result.add(dataStrings);
        }
        return result;
    }

    private void tryToBeLeader() throws KeeperException, InterruptedException {
        Thread leaderCampaignThread = new Thread(new LeaderCampaignThread());
        leaderCampaignThread.start();
    }

    class LeaderCampaignThread implements Runnable {
        private byte[] currentLeaderConnectivityInfor = null;

        @Override
        public void run() {
            System.out.println("Starting the leader Campaign");
            try {
                boolean leader = leaderLock.tryAcquireLock();
                while (!leader) {
                    byte[] leaderData = leaderLock.getLockHolderData();
                    if (currentLeaderConnectivityInfor != leaderData) {
                        currentLeaderConnectivityInfor = leaderData;
                        setCurrentLeaderData(currentLeaderConnectivityInfor);
                    }
                    Thread.sleep(10000);
                    leader = leaderLock.tryAcquireLock();
                }
                System.out.println("I got the leader lock. Now acting as primary");
                isLeader.set(true);
                currentLeaderConnectivityInfor = null;
            } catch (Exception e) {
            }
        }
    }
}
