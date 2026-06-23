package com.example;

public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Starting dummy job for 60 seconds...");
        for (int i = 0; i < 60; i++) {
            System.out.println("Progress: " + i + "/60");
            Thread.sleep(1000);
        }
        System.out.println("Finished dummy job.");
    }
}
