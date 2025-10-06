package mf.minefriend.chat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * A simple utility to test the network connection to the LLM server.
 * To run this, right-click this file in your IDE and select "Run ConnectionTester.main()".
 * Watch the console for the output.
 */
public class ConnectionTester {

    // --- CONFIGURE THIS ---
    // Make sure this IP and Port match your LLM server exactly.
    private static final String LLM_SERVER_IP = "26.126.73.192";
    private static final int LLM_SERVER_PORT = 1234;
    private static final int TIMEOUT_MS = 5000; // 5 seconds

    public static void main(String[] args) {
        System.out.println("=============================================");
        System.out.println("Starting connection test to " + LLM_SERVER_IP + ":" + LLM_SERVER_PORT + "...");

        try (Socket socket = new Socket()) {
            // Attempt to connect with a specific timeout
            socket.connect(new InetSocketAddress(LLM_SERVER_IP, LLM_SERVER_PORT), TIMEOUT_MS);

            // If we get here, the connection was successful
            System.out.println("\nSUCCESS! A connection was successfully established.");
            System.out.println("This means your network, Radmin VPN, and IP/Port are all correct.");
            System.out.println("The issue might be with the LLM server application itself being slow or hung.");

        } catch (SocketTimeoutException e) {
            // This happens if the server is reachable but not responding (e.g., firewall)
            System.err.println("\nFAILURE: Connection Timed Out.");
            System.err.println("This means we can see the server, but something is blocking the connection.");
            System.err.println("The MOST LIKELY cause is a firewall on the computer running the LLM.");
            System.err.println("Try temporarily disabling the Windows Firewall on that machine and run this test again.");

        } catch (IOException e) {
            // This happens for other network errors (e.g., wrong IP, server not running)
            System.err.println("\nFAILURE: An IO Exception occurred.");
            System.err.println("This could be due to several reasons:");
            System.err.println("1. Is the Radmin VPN IP address '" + LLM_SERVER_IP + "' still correct?");
            System.err.println("2. Is the LLM Server application (LM Studio, etc.) actually running?");
            System.err.println("3. Is the port number '" + LLM_SERVER_PORT + "' correct?");
            System.err.println("\nError Details: " + e.getMessage());
        } finally {
            System.out.println("=============================================");
        }
    }
}
