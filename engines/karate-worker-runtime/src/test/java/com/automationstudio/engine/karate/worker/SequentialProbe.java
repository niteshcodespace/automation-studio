package com.automationstudio.engine.karate.worker;
import java.util.concurrent.atomic.AtomicInteger;
public final class SequentialProbe{private static final AtomicInteger ACTIVE=new AtomicInteger(),MAX=new AtomicInteger();public static void run(){int active=ACTIVE.incrementAndGet();MAX.accumulateAndGet(active,Math::max);try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{ACTIVE.decrementAndGet();}}static void reset(){ACTIVE.set(0);MAX.set(0);}static int maximum(){return MAX.get();}private SequentialProbe(){}}
