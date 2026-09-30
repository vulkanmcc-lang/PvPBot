package com.pvpbot.ai;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

public class ObjectPool {
    private static final int INITIAL_LOCATION_POOL = 32;
    private static final int INITIAL_VECTOR_POOL = 64;
    private static final int INITIAL_LIST_POOL = 16;

    private static final int MAX_POOL_SIZE_PER_THREAD = 128;

    private static final int MAX_POOLED_LIST_SIZE = 1024;

    private final Logger logger;

    private final ThreadLocal<ArrayDeque<PooledLocation>> locationPool =
            ThreadLocal.withInitial(() -> {
                ArrayDeque<PooledLocation> d = new ArrayDeque<>(MAX_POOL_SIZE_PER_THREAD);
                for (int i = 0; i < INITIAL_LOCATION_POOL; i++) {
                    PooledLocation pl = new PooledLocation(this);
                    pl.inPool.set(true);
                    d.addFirst(pl);
                }
                return d;
            });

    private final ThreadLocal<ArrayDeque<PooledVector>> vectorPool =
            ThreadLocal.withInitial(() -> {
                ArrayDeque<PooledVector> d = new ArrayDeque<>(MAX_POOL_SIZE_PER_THREAD);
                for (int i = 0; i < INITIAL_VECTOR_POOL; i++) {
                    PooledVector pv = new PooledVector(this);
                    pv.inPool.set(true);
                    d.addFirst(pv);
                }
                return d;
            });

    private final ThreadLocal<ArrayDeque<PooledArrayList<?>>> arrayListPool =
            ThreadLocal.withInitial(() -> {
                ArrayDeque<PooledArrayList<?>> d = new ArrayDeque<>(MAX_POOL_SIZE_PER_THREAD);
                for (int i = 0; i < INITIAL_LIST_POOL; i++) {
                    PooledArrayList<?> pal = new PooledArrayList<>(this);
                    pal.inPool.set(true);
                    d.addFirst(pal);
                }
                return d;
            });

    private final LongAdder locationHits = new LongAdder();
    private final LongAdder locationMisses = new LongAdder();
    private final LongAdder locationBorrows = new LongAdder();
    private final LongAdder locationReleases = new LongAdder();

    private final LongAdder vectorHits = new LongAdder();
    private final LongAdder vectorMisses = new LongAdder();
    private final LongAdder vectorBorrows = new LongAdder();
    private final LongAdder vectorReleases = new LongAdder();

    private final LongAdder listHits = new LongAdder();
    private final LongAdder listMisses = new LongAdder();
    private final LongAdder listBorrows = new LongAdder();
    private final LongAdder listReleases = new LongAdder();

    private final AtomicBoolean warnedDoubleReleaseLocation = new AtomicBoolean();
    private final AtomicBoolean warnedDoubleReleaseVector = new AtomicBoolean();
    private final AtomicBoolean warnedDoubleReleaseList = new AtomicBoolean();
    private final AtomicBoolean warnedCorruptState = new AtomicBoolean();

    public ObjectPool(Logger logger) {
        this.logger = logger != null ? logger : Logger.getLogger("PvPBot.ObjectPool");
    }

    public ObjectPool() {
        this(null);
    }

    public PooledLocation getLocation(World world, double x, double y, double z) {
        PooledLocation loc = locationPool.get().pollFirst();
        if (loc == null) {
            locationMisses.increment();
            loc = new PooledLocation(this);
        } else if (!loc.inPool.compareAndSet(true, false)) {
            warnCorruptState("PooledLocation");
            locationMisses.increment();
            loc = new PooledLocation(this);
        } else {
            locationHits.increment();
        }
        locationBorrows.increment();
        loc.init(world, x, y, z);
        loc.setYaw(0f);
        loc.setPitch(0f);
        return loc;
    }

    public PooledLocation getLocation(World world, double x, double y, double z, float yaw, float pitch) {
        PooledLocation loc = getLocation(world, x, y, z);
        loc.setYaw(yaw);
        loc.setPitch(pitch);
        return loc;
    }

    public PooledLocation cloneLocation(Location original) {
        if (original == null) return null;
        return getLocation(original.getWorld(), original.getX(), original.getY(), original.getZ(),
                original.getYaw(), original.getPitch());
    }

    public void releaseLocation(Location loc) {
        if (!(loc instanceof PooledLocation pl)) return;
        if (!pl.inPool.compareAndSet(false, true)) {
            warnDoubleRelease(warnedDoubleReleaseLocation, "PooledLocation");
            return;
        }
        locationReleases.increment();
        ArrayDeque<PooledLocation> deque = locationPool.get();
        if (deque.size() >= MAX_POOL_SIZE_PER_THREAD) {
            return;
        }
        pl.reset();
        deque.addFirst(pl);
    }

    public PooledVector getVector() {
        return getVector(0, 0, 0);
    }

    public PooledVector getVector(double x, double y, double z) {
        PooledVector vec = vectorPool.get().pollFirst();
        if (vec == null) {
            vectorMisses.increment();
            vec = new PooledVector(this);
        } else if (!vec.inPool.compareAndSet(true, false)) {
            warnCorruptState("PooledVector");
            vectorMisses.increment();
            vec = new PooledVector(this);
        } else {
            vectorHits.increment();
        }
        vectorBorrows.increment();
        vec.init(x, y, z);
        return vec;
    }

    public PooledVector cloneVector(Vector original) {
        if (original == null) return getVector();
        return getVector(original.getX(), original.getY(), original.getZ());
    }

    public void releaseVector(Vector vec) {
        if (!(vec instanceof PooledVector pv)) return;
        if (!pv.inPool.compareAndSet(false, true)) {
            warnDoubleRelease(warnedDoubleReleaseVector, "PooledVector");
            return;
        }
        vectorReleases.increment();
        ArrayDeque<PooledVector> deque = vectorPool.get();
        if (deque.size() >= MAX_POOL_SIZE_PER_THREAD) {
            return;
        }
        pv.reset();
        deque.addFirst(pv);
    }

    @SuppressWarnings("unchecked")
    public <T> PooledArrayList<T> getArrayList() {
        PooledArrayList<?> raw = arrayListPool.get().pollFirst();
        listBorrows.increment();
        if (raw == null) {
            listMisses.increment();
            return new PooledArrayList<>(this);
        }
        if (!raw.inPool.compareAndSet(true, false)) {
            warnCorruptState("PooledArrayList");
            listMisses.increment();
            return new PooledArrayList<>(this);
        }
        listHits.increment();

        if (!raw.isEmpty()) {
            warnCorruptState("PooledArrayList(non-empty)");
            raw.clear();
        }
        return (PooledArrayList<T>) raw;
    }

    public <T> PooledArrayList<T> getArrayList(int initialCapacity) {
        PooledArrayList<T> list = getArrayList();

        list.ensureCapacity(initialCapacity);
        return list;
    }

    public <T> void releaseArrayList(PooledArrayList<T> list) {
        if (list == null) return;
        if (!list.inPool.compareAndSet(false, true)) {
            warnDoubleRelease(warnedDoubleReleaseList, "PooledArrayList");
            return;
        }
        listReleases.increment();
        if (list.size() > MAX_POOLED_LIST_SIZE) {
            return;
        }
        list.clear();
        ArrayDeque<PooledArrayList<?>> deque = arrayListPool.get();
        if (deque.size() >= MAX_POOL_SIZE_PER_THREAD) {
            return;
        }
        deque.addFirst(list);
    }

    public void logStats() {
        long locTotal = locationHits.sum() + locationMisses.sum();
        long vecTotal = vectorHits.sum() + vectorMisses.sum();
        long listTotal = listHits.sum() + listMisses.sum();

        long locOutstanding = locationBorrows.sum() - locationReleases.sum();
        long vecOutstanding = vectorBorrows.sum() - vectorReleases.sum();
        long listOutstanding = listBorrows.sum() - listReleases.sum();

        logger.info("[ObjectPool] Location: " + locationHits.sum() + "/" + locTotal + " hits, "
                + locOutstanding + " outstanding"
                + " | Vector: " + vectorHits.sum() + "/" + vecTotal + " hits, "
                + vecOutstanding + " outstanding"
                + " | ArrayList: " + listHits.sum() + "/" + listTotal + " hits, "
                + listOutstanding + " outstanding");
    }

    public void resetStats() {
        locationHits.reset();
        locationMisses.reset();
        locationBorrows.reset();
        locationReleases.reset();
        vectorHits.reset();
        vectorMisses.reset();
        vectorBorrows.reset();
        vectorReleases.reset();
        listHits.reset();
        listMisses.reset();
        listBorrows.reset();
        listReleases.reset();
    }

    private void warnDoubleRelease(AtomicBoolean flag, String type) {
        if (flag.compareAndSet(false, true)) {
            logger.warning("[ObjectPool] double release of " + type
                    + " ignored (further occurrences suppressed)");
        }
    }

    private void warnCorruptState(String type) {
        if (warnedCorruptState.compareAndSet(false, true)) {
            logger.warning("[ObjectPool] corrupt pool state detected for " + type
                    + "; abandoned instance (further occurrences suppressed)");
        }
    }

    public static class PooledLocation extends Location implements AutoCloseable {
        final AtomicBoolean inPool = new AtomicBoolean(false);
        private final ObjectPool pool;

        PooledLocation(ObjectPool pool) {
            super(null, 0, 0, 0);
            this.pool = pool;
        }

        void init(World world, double x, double y, double z) {
            this.setWorld(world);
            this.setX(x);
            this.setY(y);
            this.setZ(z);
        }

        void reset() {
            this.setWorld(null);
            this.setX(0);
            this.setY(0);
            this.setZ(0);
            this.setYaw(0);
            this.setPitch(0);
        }

        @Override
        public void close() {
            pool.releaseLocation(this);
        }
    }

    public static class PooledVector extends Vector implements AutoCloseable {
        final AtomicBoolean inPool = new AtomicBoolean(false);
        private final ObjectPool pool;

        PooledVector(ObjectPool pool) {
            super(0, 0, 0);
            this.pool = pool;
        }

        void init(double x, double y, double z) {
            this.setX(x);
            this.setY(y);
            this.setZ(z);
        }

        void reset() {
            this.setX(0);
            this.setY(0);
            this.setZ(0);
        }

        @Override
        public void close() {
            pool.releaseVector(this);
        }
    }

    public static class PooledArrayList<T> extends ArrayList<T> implements AutoCloseable {
        private static final long serialVersionUID = 1L;

        final transient AtomicBoolean inPool = new AtomicBoolean(false);
        private final transient ObjectPool pool;

        PooledArrayList(ObjectPool pool) {
            this.pool = pool;
        }

        @Override
        public void close() {
            pool.releaseArrayList(this);
        }
    }
}
