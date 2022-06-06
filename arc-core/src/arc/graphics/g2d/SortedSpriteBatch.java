package arc.graphics.g2d;

import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;

import java.util.*;
import java.util.concurrent.*;

import static arc.Core.graphics;

public class SortedSpriteBatch extends SpriteBatch{
    protected Seq<DrawRequest> requestPool = new Seq<>(10000);
    protected Seq<DrawRequest> requests = new Seq<>(DrawRequest.class);
    protected boolean sort;
    protected boolean flushing;
    protected FloatSeq requestZ = new FloatSeq();

    @Override
    protected void setSort(boolean sort){
        if(this.sort != sort){
            flush();
        }
        this.sort = sort;
    }

    @Override
    protected void setShader(Shader shader, boolean apply){
        if(!flushing && sort){
            throw new IllegalArgumentException("Shaders cannot be set while sorting is enabled. Set shaders inside Draw.run(...).");
        }
        super.setShader(shader, apply);
    }

    @Override
    protected void setBlending(Blending blending){
        this.blending = blending;
    }

    @Override
    protected void draw(Texture texture, float[] spriteVertices, int offset, int count){
        if(sort && !flushing){
            for(int i = offset; i < count; i += SPRITE_SIZE){
                DrawRequest req = obtain();
                req.z = z;
                System.arraycopy(spriteVertices, i, req.vertices, 0, req.vertices.length);
                req.texture = texture;
                req.blending = blending;
                req.run = null;
                requests.add(req);
                requestZ.add(z);
            }
        }else{
            super.draw(texture, spriteVertices, offset, count);
        }
    }

    @Override
    protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){
        if(sort && !flushing){
            DrawRequest req = obtain();
            req.x = x;
            req.y = y;
            req.z = z;
            req.originX = originX;
            req.originY = originY;
            req.width = width;
            req.height = height;
            req.color = colorPacked;
            req.rotation = rotation;
            req.region.set(region);
            req.mixColor = mixColorPacked;
            req.blending = blending;
            req.texture = null;
            req.run = null;
            requests.add(req);
            requestZ.add(z);
        }else{
            super.draw(region, x, y, originX, originY, width, height, rotation);
        }
    }

    @Override
    protected void draw(Runnable request){
        if(sort && !flushing){
            DrawRequest req = obtain();
            req.run = request;
            req.blending = blending;
            req.mixColor = mixColorPacked;
            req.color = colorPacked;
            req.z = z;
            req.texture = null;
            requests.add(req);
            requestZ.add(z);
        }else{
            super.draw(request);
        }
    }

    protected DrawRequest obtain(){
        return requestPool.size > 0 ? requestPool.pop() : new DrawRequest();
    }

    @Override
    protected void flush(){
        flushRequests();
        super.flush();
    }

    protected void flushRequests(){
        if(!flushing && !requests.isEmpty()){
            flushing = true;
            sortRequests();
            float preColor = colorPacked, preMixColor = mixColorPacked;
            Blending preBlending = blending;

            for(int j = 0; j < requests.size; j++){
                DrawRequest req = requests.items[j];

                colorPacked = req.color;
                mixColorPacked = req.mixColor;

                super.setBlending(req.blending);

                if(req.run != null){
                    req.run.run();
                }else if(req.texture != null){
                    super.draw(req.texture, req.vertices, 0, req.vertices.length);
                }else{
                    super.draw(req.region, req.x, req.y, req.originX, req.originY, req.width, req.height, req.rotation);
                }
            }

            colorPacked = preColor;
            mixColorPacked = preMixColor;
            color.abgr8888(colorPacked);
            mixColor.abgr8888(mixColorPacked);
            blending = preBlending;

            requestPool.addAll(requests);
            requests.size = 0;
            requestZ.size = 0;

            flushing = false;
        }
    }

    public static boolean debug = false, dump = false, mt = true, radix = false, iimap = true;
    Point3[] contiguous = new Point3[2048], contiguousCopy = new Point3[2048];
    { for(int i = 0; i < contiguous.length; i++) contiguous[i] = new Point3(); }
    final ForkJoinPool commonPool = ForkJoinPool.commonPool();
    DrawRequest[] copy = new DrawRequest[0];
    int[] locs = new int[contiguous.length];

    protected void sortRequests(){
        if (mt) {
            sortRequestsMT();
        } else {
            sortRequestsST();
        }
    }

    protected void sortRequestsMT(){
        Time.mark(); Time.mark();
        final int numRequests = requests.size;
        if(copy.length < numRequests) copy = new DrawRequest[numRequests + (numRequests >> 3)];
        final DrawRequest[] items = requests.items, itemCopy = copy;
        final float[] itemZ = requestZ.items;
        final Future<?> initTask = commonPool.submit(() -> System.arraycopy(items, 0, itemCopy, 0, numRequests));

        float t_init = Time.elapsed(); Time.mark();

        Point3[] contiguous = this.contiguous;
        int ci = 0, cl = contiguous.length;
        float z = itemZ[0];
        int startI = 0;
        // Point3: <z, index, length>
        for(int i = 1; i < numRequests; i++){
            if(itemZ[i] != z){ // if contiguous section should end
                contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, i - startI);
                if(ci >= cl){
                    contiguous = Arrays.copyOf(contiguous, cl <<= 1);
                    for(int j = ci; j < cl; j++) contiguous[j] = new Point3();
                }
                z = itemZ[i];
                startI = i;
            }
        }
        contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, numRequests - startI);
        this.contiguous = contiguous;
        float t_cont = Time.elapsed(); Time.mark();

        final int L = ci;

        if(contiguousCopy.length < contiguous.length) this.contiguousCopy = new Point3[contiguous.length];

        final Point3[] sorted = radix ? RadixSort.radixSortST(contiguous, contiguousCopy, L) :
                iimap ? CountingSort.countingSortMap(contiguous, contiguousCopy, L) : CountingSort.countingSortST(contiguous, contiguousCopy, L);

        float t_sort = Time.elapsed(); Time.mark();

        if(locs.length < L + 1) locs = new int[L + L / 10];
        final int[] locs = this.locs;
        for(int i = 0; i < L; i++){
            locs[i + 1] = locs[i] + sorted[i].z;
        }
        try {
            initTask.get();
        } catch (Exception ignored){
            System.arraycopy(items, 0, itemCopy, 0, numRequests);
        }
        PopulateTask.tasks = sorted;
        PopulateTask.src = itemCopy;
        PopulateTask.dest = items;
        PopulateTask.locs = locs;
        commonPool.invoke(new PopulateTask(0, L));
        float t_cpy = Time.elapsed();
        float elapsed = Time.elapsed();
        if(debug) {
            Log.debug("total: @ | size: @ -> @ | init: @ | contiguous: @ | sort: @ | populate: @",
                    elapsed, numRequests, L, t_init, t_cont, t_sort, t_cpy);
            debugInfo();
        }
    }
    protected void sortRequestsST(){ // Non-threaded implementation for weak devices
        Time.mark(); Time.mark();
        final int numRequests = requests.size;
        if(copy.length < numRequests) copy = new DrawRequest[numRequests + (numRequests >> 3)];
        final DrawRequest[] items = copy;
        final float[] itemZ = this.requestZ.items;
        System.arraycopy(requests.items, 0, items, 0, numRequests);
        float t_init = Time.elapsed(); Time.mark();
        Point3[] contiguous = this.contiguous;
        int ci = 0, cl = contiguous.length;
        float z = itemZ[0];
        int startI = 0;
        // Point3: <z, index, length>
        for(int i = 1; i < numRequests; i++){
            if(itemZ[i] != z){ // if contiguous section should end
                contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, i - startI);
                if(ci >= cl){
                    contiguous = Arrays.copyOf(contiguous, cl <<= 1);
                    for(int j = ci; j < cl; j++) contiguous[j] = new Point3();
                }
                z = itemZ[i];
                startI = i;
            }
        }
        contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, numRequests - startI);
        this.contiguous = contiguous;
        float t_cont = Time.elapsed(); Time.mark();

        final int L = ci;

        if(contiguousCopy.length < contiguous.length) contiguousCopy = new Point3[contiguous.length];

        final Point3[] sorted = radix ? RadixSort.radixSortST(contiguous, contiguousCopy, L) : CountingSort.countingSortST(contiguous, contiguousCopy, L);
        //Arrays.sort(contiguous, 0, L, Structs.comparingInt(p -> p.x));

        float t_sort = Time.elapsed(); Time.mark();

        int ptr = 0;
        final DrawRequest[] dest = requests.items;
        for(int i = 0; i < L; i++){
            final Point3 point = sorted[i];
            final int length = point.z;
            if(length < 10){
                final int end = point.y + length;
                for(int sj = point.y, dj = ptr; sj < end ; sj++, dj++){
                    requests.items[dj] = items[sj];
                }
            } else System.arraycopy(items, point.y, dest, ptr, length);
            ptr += point.z;
        }
        float t_cpy = Time.elapsed();
        float elapsed = Time.elapsed();
        if(debug) {
            Log.debug("total: @ | size: @ -> @ | init: @ | contiguous: @ | sort: @ | populate: @",
                    elapsed, numRequests, L, t_init, t_cont, t_sort, t_cpy);
            debugInfo();
        }
    }
    public void debugInfo() {
        if (dump) {
            StringBuilder out = new StringBuilder("Result dump:\n");
            for (DrawRequest dr : requests) {
                out.append(dr.z).append(" ");
            }
            Log.debug(out);
            debug = dump = false;
        }
    }
    static class CountingSort{
        private static int keyLength = 100; // The maximum number of unique values to sort
        private static int[] keys = new int[keyLength], locs = new int[keyLength], sortedToInsertion = new int[keyLength];
        public static Point3[] countingSortST(final Point3[] arr, final Point3[] swap, final int end){
            int unique = 0, keyLength = CountingSort.keyLength;
            int[] locs = CountingSort.locs, keys = CountingSort.keys, sortedToInsertion = CountingSort.sortedToInsertion;
            for(int i = 0; i < end; i++){
                int num = arr[i].x;
                int loc = Arrays.binarySearch(keys, 0, unique, num);
                if(loc < 0){
                    loc = -loc - 1;
                    if(unique >= keyLength){
                        CountingSort.keyLength = keyLength = keyLength * 3 / 2;
                        CountingSort.keys = keys = Arrays.copyOf(keys, keyLength);
                        CountingSort.locs = locs = Arrays.copyOf(locs, keyLength);
                        CountingSort.sortedToInsertion = sortedToInsertion = Arrays.copyOf(sortedToInsertion, keyLength);
                    }
                    System.arraycopy(keys, loc, keys, loc + 1, unique - loc);
                    System.arraycopy(sortedToInsertion, loc, sortedToInsertion, loc + 1, unique - loc);
                    arr[i].x = sortedToInsertion[loc] = unique;
                    keys[loc] = num;
                    locs[unique++] = 1;
                } else {
                    locs[arr[i].x = sortedToInsertion[loc]]++;
                }
            }
            final int[] locs2 = locs, sortedToInsertion2 = sortedToInsertion;
            for(int i = 1; i < unique; i++){
                locs2[sortedToInsertion2[i]] += locs2[sortedToInsertion2[i - 1]];
            }
            for(int i = end - 1; i >= 0; i--){
                Point3 curr = arr[i];
                swap[--locs2[curr.x]] = curr;
            }
            return swap;
        }
        private static final IntIntMap counts = new IntIntMap(100);
        private static Point2[] entries = new Point2[100];
        static { for(int i = 0; i < entries.length; i++) entries[i] = new Point2(); }
        public static Point3[] countingSortMap(final Point3[] arr, final Point3[] swap, final int end){
            int[] locs = CountingSort.locs;
            final IntIntMap counts = CountingSort.counts;
            counts.clear();

            int unique = 0;
            for(int i = 0; i < end; i++){
                int loc = counts.getOrPut(arr[i].x, unique);
                arr[i].x = loc;
                if(loc == unique){
                    if(unique >= locs.length){
                        locs = Arrays.copyOf(locs, unique * 3 / 2);
                    }
                    locs[unique++] = 1;
                }else{
                    locs[loc]++;
                }
            }
            CountingSort.locs = locs;

            if(entries.length < unique){
                final int prevLength = entries.length;
                entries = Arrays.copyOf(entries, unique * 3 / 2);
                final Point2[] entries = CountingSort.entries;
                for(int i = prevLength; i < entries.length; i++) entries[i] = new Point2();
            }
            final Point2[] entries = CountingSort.entries;

            final IntIntMap.Entries countEntries = counts.entries();
            final IntIntMap.Entry entry = countEntries.next();
            entries[0].set(entry.key, entry.value);
            int j = 1;
            while(countEntries.hasNext){
                countEntries.next(); // it returns the same entry over and over again.
                entries[j++].set(entry.key, entry.value);
            }
            Arrays.sort(entries, 0, unique, Structs.comparingInt(p -> p.x));

            int prev = entries[0].y, next;
            for(int i = 1; i < unique; i++){
                locs[next = entries[i].y] += locs[prev];
                prev = next;
            }
            for(int i = end - 1; i >= 0; i--){
                final Point3 curr = arr[i];
                swap[--locs[curr.x]] = curr;
            }
            return swap;
        }
    }
    static class RadixSort{
        private final static int bits = 13, radix_length = 1 << bits, mask = radix_length - 1, runs = (26 + bits - 1) / bits;
        private final static int[] radixBuckets = new int[radix_length], bucketsBlank = new int[radix_length];
        // For up to z = 256, we only need to radix the last 26 bits.
        public static Point3[] radixSortST(Point3[] arr, Point3[] swap, final int end){
            final int[] keys = RadixSort.radixBuckets;
            for(int d = 0; d < runs; d++){
                final Point3[] arr2 = arr, swap2 = swap;
                System.arraycopy(bucketsBlank, 0, keys, 0, radix_length);
                for(int i = 0; i < end; i++){
                    keys[arr2[i].x & mask]++;
                }
                for(int i = 1; i < radix_length; i++){
                    keys[i] += keys[i - 1];
                }
                for(int i = end - 1; i >= 0; i--){
                    Point3 curr = arr2[i];
                    swap2[--keys[curr.x & mask]] = curr;
                    curr.x >>= bits;
                }
                arr = swap2;
                swap = arr2;
            }
            return arr;
        }
    }
    static class PopulateTask extends RecursiveAction{
        int from, to;
        static Point3[] tasks;
        static DrawRequest[] src;
        static DrawRequest[] dest;
        static int[] locs;
        //private static final int threshold = 256;
        PopulateTask(int from, int to){
            this.from = from;
            this.to = to;
        }
        @Override
        /*
        protected void compute(){
            if(to - from > threshold){
                int mid = (from + to) >> 1;
                PopulateTask t1 = new PopulateTask(from, mid), t2 = new PopulateTask(mid, to);
                invokeAll(t1, t2);
            } else {
                Point3[] tasks = PopulateTask.tasks;
                int[] locs = PopulateTask.locs;
                for(int i = from; i < to; i++){
                    Point3 point = tasks[i];
                    System.arraycopy(src, point.y, dest, locs[i], point.z);
                }
            }
        }
         */
        protected void compute(){
            final int[] locs = PopulateTask.locs;
            if(to - from > 1 && locs[to] - locs[from] > 2048){
                final int half = (locs[to] + locs[from]) >> 1;
                int mid = Arrays.binarySearch(locs, from, to, half);
                if(mid < 0) mid = -mid - 1;
                if(mid != from && mid != to) {
                    invokeAll(new PopulateTask(from, mid), new PopulateTask(mid, to));
                    return;
                }
            }
            final DrawRequest[] src = PopulateTask.src, dest = PopulateTask.dest;
            final Point3[] tasks = PopulateTask.tasks;
            for(int i = from; i < to; i++){
                final Point3 point = tasks[i];
                final int length = point.z;
                if(length < 10){
                    final int end = point.y + length;
                    for(int sj = point.y, dj = locs[i]; sj < end ; sj++, dj++){
                        dest[dj] = src[sj];
                    }
                } else System.arraycopy(src, point.y, dest, locs[i], Math.min(length, dest.length - locs[i]));
            }
        }
    }
}
