/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.crac.CheckpointException;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;

public class CalculateAverage_rvansa {
    private static final AtomicBoolean forceDryRun = new AtomicBoolean(true);
    private static final Resource dryRunResource = new Resource() {
        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            if (forceDryRun.get()) {
                throw new Exception("Forcing dry-run");
            }
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) throws Exception {
        }
    };
    public static final int NUM_WARMUPS = 10;
    public static final int NUM_DRY_CHECKPOINTS = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.exit(1);
        }
        Class<?> mainClass = Class.forName(args[0]);
        Method main = mainClass.getMethod("main", String[].class);
        String[] shifted = Stream.of(args).skip(1).toArray(String[]::new);

        for (int i = 0; i < NUM_WARMUPS; ++i) {
            runWarmup(mainClass, main, shifted);
        }

        // Register a resource after warmups to ensure it's the last one
        Core.getGlobalContext().register(dryRunResource);
        for (int i = 0; i < NUM_DRY_CHECKPOINTS; ++i) {
            try {
                Core.checkpointRestore();
            }
            catch (CheckpointException e) {
                // expected to fail with dry run
                if (e.getSuppressed().length != 1) {
                    throw e;
                }
            }
        }
        // Let compile threads finish
        Thread.sleep(1000);
        forceDryRun.set(false);

        Core.checkpointRestore();
        main.invoke(null, (Object) shifted);
    }

    private static void runWarmup(Class<?> mainClass, Method main, String[] shifted) throws IllegalAccessException, InvocationTargetException {
        // Some benchmarks close System.out
        PrintStream savedOutput = saveSystemOut();
        main.invoke(null, (Object) shifted);
        System.setOut(savedOutput);

        // Some store collections in static fields
        Arrays.stream(mainClass.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .filter(f -> Collection.class.isAssignableFrom(f.getType()))
                .forEach(f -> {
                    f.setAccessible(true);
                    try {
                        Collection<?> c = (Collection<?>) f.get(null);
                        if (c != null) {
                            c.clear();
                        }
                    }
                    catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
        LeakedExecturors.EXECUTORS.forEach(es -> {
            es.shutdownNow();
            try {
                es.awaitTermination(10, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        LeakedExecturors.EXECUTORS.clear();

        // Shutdown leaked FJPs
        Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t != Thread.currentThread())
                .forEach(thread -> {
                    if (thread instanceof ForkJoinWorkerThread fjwt) {
                        fjwt.getPool().shutdownNow();
                        try {
                            fjwt.getPool().awaitTermination(10, TimeUnit.SECONDS);
                            thread.join();
                        }
                        catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
    }

    private static PrintStream saveSystemOut() {
        PrintStream original = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        return original;
    }
}
