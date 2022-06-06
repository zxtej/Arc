package arc.struct;

import arc.math.Mathf;
import arc.util.ArcRuntimeException;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * An unordered map where the keys and values are ints. This implementation is a cuckoo hash map using 3 hashes, random walking,
 * and a small stash for problematic keys. No allocation is done except when growing the table size. <br>
 * <br>
 * This map performs very fast get, containsKey, and remove (typically O(1), worst case O(log(n))). Put may be a bit slower,
 * depending on hash collisions. Load factors greater than 0.91 greatly increase the chances the map will have to rehash to the
 * next higher POT size.
 * @author Nathan Sweet
 */
public class IntIntMap implements Iterable<IntIntMap.Entry>{
    private static final int PRIME1 = 0xbe1f14b1;
    private static final int PRIME2 = 0xb4b82e39;
    private static final int PRIME3 = 0xced1c241;
    private static final int EMPTY = 0;

    public int size;

    int[] keyTable, valueTable;
    int capacity, stashSize;
    int zeroValue;
    boolean hasZeroValue;

    private float loadFactor;
    private int hashShift, mask, threshold;
    private int stashCapacity;
    private int pushIterations;

    private Entries entries1, entries2;
    private Values values1, values2;
    private Keys keys1, keys2;

    /** Creates a new map with an initial capacity of 51 and a load factor of 0.8. */
    public IntIntMap(){
        this(51, 0.8f);
    }

    /**
     * Creates a new map with a load factor of 0.8.
     * @param initialCapacity If not a power of two, it is increased to the next nearest power of two.
     */
    public IntIntMap(int initialCapacity){
        this(initialCapacity, 0.8f);
    }

    /**
     * Creates a new map with the specified initial capacity and load factor. This map will hold initialCapacity items before
     * growing the backing table.
     * @param initialCapacity If not a power of two, it is increased to the next nearest power of two.
     */
    public IntIntMap(int initialCapacity, float loadFactor){
        if(initialCapacity < 0) throw new IllegalArgumentException("initialCapacity must be >= 0: " + initialCapacity);
        initialCapacity = Mathf.nextPowerOfTwo((int)Math.ceil(initialCapacity / loadFactor));
        if(initialCapacity > 1 << 30)
            throw new IllegalArgumentException("initialCapacity is too large: " + initialCapacity);
        capacity = initialCapacity;

        if(loadFactor <= 0) throw new IllegalArgumentException("loadFactor must be > 0: " + loadFactor);
        this.loadFactor = loadFactor;

        threshold = (int)(capacity * loadFactor);
        mask = capacity - 1;
        hashShift = 31 - Integer.numberOfTrailingZeros(capacity);
        stashCapacity = Math.max(3, (int)Math.ceil(Math.log(capacity)) * 2);
        pushIterations = Math.max(Math.min(capacity, 8), (int)Math.sqrt(capacity) / 8);

        keyTable = new int[capacity + stashCapacity];
        valueTable = new int[keyTable.length];
    }

    /** Creates a new map identical to the specified map. */
    public IntIntMap(IntIntMap map){
        this((int)Math.floor(map.capacity * map.loadFactor), map.loadFactor);
        stashSize = map.stashSize;
        System.arraycopy(map.keyTable, 0, keyTable, 0, map.keyTable.length);
        System.arraycopy(map.valueTable, 0, valueTable, 0, map.valueTable.length);
        size = map.size;
        zeroValue = map.zeroValue;
        hasZeroValue = map.hasZeroValue;
    }

    public static IntIntMap with(int... values){
        IntIntMap map = new IntIntMap();
        for(int i = 0; i < values.length; i += 2){
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    /** Only inserts into the map if value is not present.
    * @param key The key.
    * @param value The value.
    * @return The associated value if key is present in the map, else {@code value}.
    * */
    public int getOrPut(final int key, final int value){
        if(key == 0){
            if(!hasZeroValue){
                zeroValue = value;
                hasZeroValue = true;
                size++;
            }
            return zeroValue;
        }

        final int[] keyTable = this.keyTable, valueTable = this.valueTable;

        // Check for existing keys.
        int index1 = key & mask;
        int key1 = keyTable[index1];
        if(key == key1){
            return valueTable[index1];
        }

        int index2 = hash2(key);
        int key2 = keyTable[index2];
        if(key == key2){
            return valueTable[index2];
        }

        int index3 = hash3(key);
        int key3 = keyTable[index3];
        if(key == key3){
            return valueTable[index3];
        }

        // Update key in the stash.
        for(int i = capacity, n = i + stashSize; i < n; i++){
            if(key == keyTable[i]){
                return valueTable[i];
            }
        }

        // Check for empty buckets.
        if(key1 == EMPTY){
            keyTable[index1] = key;
            valueTable[index1] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return value;
        }

        if(key2 == EMPTY){
            keyTable[index2] = key;
            valueTable[index2] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return value;
        }

        if(key3 == EMPTY){
            keyTable[index3] = key;
            valueTable[index3] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return value;
        }

        push(key, value, index1, key1, index2, key2, index3, key3);
        return value;
    }

    public void put(int key, int value){
        if(key == 0){
            zeroValue = value;
            if(!hasZeroValue){
                hasZeroValue = true;
                size++;
            }
            return;
        }

        int[] keyTable = this.keyTable;

        // Check for existing keys.
        int index1 = key & mask;
        int key1 = keyTable[index1];
        if(key == key1){
            valueTable[index1] = value;
            return;
        }

        int index2 = hash2(key);
        int key2 = keyTable[index2];
        if(key == key2){
            valueTable[index2] = value;
            return;
        }

        int index3 = hash3(key);
        int key3 = keyTable[index3];
        if(key == key3){
            valueTable[index3] = value;
            return;
        }

        // Update key in the stash.
        for(int i = capacity, n = i + stashSize; i < n; i++){
            if(key == keyTable[i]){
                valueTable[i] = value;
                return;
            }
        }

        // Check for empty buckets.
        if(key1 == EMPTY){
            keyTable[index1] = key;
            valueTable[index1] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        if(key2 == EMPTY){
            keyTable[index2] = key;
            valueTable[index2] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        if(key3 == EMPTY){
            keyTable[index3] = key;
            valueTable[index3] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        push(key, value, index1, key1, index2, key2, index3, key3);
    }

    public void putAll(IntIntMap map){
        for(Entry entry : map.entries())
            put(entry.key, entry.value);
    }

    /** Skips checks for existing keys. */
    private void putResize(int key, int value){
        if(key == 0){
            zeroValue = value;
            hasZeroValue = true;
            return;
        }

        // Check for empty buckets.
        int index1 = key & mask;
        int key1 = keyTable[index1];
        if(key1 == EMPTY){
            keyTable[index1] = key;
            valueTable[index1] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        int index2 = hash2(key);
        int key2 = keyTable[index2];
        if(key2 == EMPTY){
            keyTable[index2] = key;
            valueTable[index2] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        int index3 = hash3(key);
        int key3 = keyTable[index3];
        if(key3 == EMPTY){
            keyTable[index3] = key;
            valueTable[index3] = value;
            if(size++ >= threshold) resize(capacity << 1);
            return;
        }

        push(key, value, index1, key1, index2, key2, index3, key3);
    }

    private void push(int insertKey, int insertValue, int index1, int key1, int index2, int key2, int index3, int key3){
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        int mask = this.mask;

        // Push keys until an empty bucket is found.
        int evictedKey;
        int evictedValue;
        int i = 0, pushIterations = this.pushIterations;
        do{
            // Replace the key and value for one of the hashes.
            switch(Mathf.random(2)){
                case 0:
                    evictedKey = key1;
                    evictedValue = valueTable[index1];
                    keyTable[index1] = insertKey;
                    valueTable[index1] = insertValue;
                    break;
                case 1:
                    evictedKey = key2;
                    evictedValue = valueTable[index2];
                    keyTable[index2] = insertKey;
                    valueTable[index2] = insertValue;
                    break;
                default:
                    evictedKey = key3;
                    evictedValue = valueTable[index3];
                    keyTable[index3] = insertKey;
                    valueTable[index3] = insertValue;
                    break;
            }

            // If the evicted key hashes to an empty bucket, put it there and stop.
            index1 = evictedKey & mask;
            key1 = keyTable[index1];
            if(key1 == EMPTY){
                keyTable[index1] = evictedKey;
                valueTable[index1] = evictedValue;
                if(size++ >= threshold) resize(capacity << 1);
                return;
            }

            index2 = hash2(evictedKey);
            key2 = keyTable[index2];
            if(key2 == EMPTY){
                keyTable[index2] = evictedKey;
                valueTable[index2] = evictedValue;
                if(size++ >= threshold) resize(capacity << 1);
                return;
            }

            index3 = hash3(evictedKey);
            key3 = keyTable[index3];
            if(key3 == EMPTY){
                keyTable[index3] = evictedKey;
                valueTable[index3] = evictedValue;
                if(size++ >= threshold) resize(capacity << 1);
                return;
            }

            if(++i == pushIterations) break;

            insertKey = evictedKey;
            insertValue = evictedValue;
        }while(true);

        putStash(evictedKey, evictedValue);
    }

    private void putStash(int key, int value){
        if(stashSize == stashCapacity){
            // Too many pushes occurred and the stash is full, increase the table size.
            resize(capacity << 1);
            putResize(key, value);
            return;
        }
        // Store key in the stash.
        int index = capacity + stashSize;
        keyTable[index] = key;
        valueTable[index] = value;
        stashSize++;
        size++;
    }

    public int get(int key){
        return get(key, 0);
    }

    /** @param defaultValue Returned if the key was not associated with a value. */
    public int get(int key, int defaultValue){
        if(key == 0){
            if(!hasZeroValue) return defaultValue;
            return zeroValue;
        }
        int index = key & mask;
        if(keyTable[index] != key){
            index = hash2(key);
            if(keyTable[index] != key){
                index = hash3(key);
                if(keyTable[index] != key) return getStash(key, defaultValue);
            }
        }
        return valueTable[index];
    }

    private int getStash(int key, int defaultValue){
        int[] keyTable = this.keyTable;
        for(int i = capacity, n = i + stashSize; i < n; i++)
            if(key == keyTable[i]) return valueTable[i];
        return defaultValue;
    }

    public int increment(int key){
        return increment(key, 1);
    }

    public int increment(int key, int amount){
        return increment(key, 0, amount);
    }

    /**
     * Returns the key's current value and increments the stored value. If the key is not in the map, defaultValue + increment is
     * put into the map.
     */
    public int increment(int key, int defaultValue, int increment){
        if(key == 0){
            if(hasZeroValue){
                int value = zeroValue;
                zeroValue += increment;
                return value;
            }else{
                hasZeroValue = true;
                zeroValue = defaultValue + increment;
                ++size;
                return defaultValue;
            }
        }
        int index = key & mask;
        if(key != keyTable[index]){
            index = hash2(key);
            if(key != keyTable[index]){
                index = hash3(key);
                if(key != keyTable[index]) return getAndIncrementStash(key, defaultValue, increment);
            }
        }
        int value = valueTable[index];
        valueTable[index] = value + increment;
        return value;
    }

    private int getAndIncrementStash(int key, int defaultValue, int increment){
        int[] keyTable = this.keyTable;
        for(int i = capacity, n = i + stashSize; i < n; i++)
            if(key == keyTable[i]){
                int value = valueTable[i];
                valueTable[i] = value + increment;
                return value;
            }
        put(key, defaultValue + increment);
        return defaultValue;
    }

    public int remove(int key){
        return remove(key, 0);
    }

    public int remove(int key, int defaultValue){
        if(key == 0){
            if(!hasZeroValue) return defaultValue;
            hasZeroValue = false;
            size--;
            return zeroValue;
        }

        int index = key & mask;
        if(key == keyTable[index]){
            keyTable[index] = EMPTY;
            int oldValue = valueTable[index];
            size--;
            return oldValue;
        }

        index = hash2(key);
        if(key == keyTable[index]){
            keyTable[index] = EMPTY;
            int oldValue = valueTable[index];
            size--;
            return oldValue;
        }

        index = hash3(key);
        if(key == keyTable[index]){
            keyTable[index] = EMPTY;
            int oldValue = valueTable[index];
            size--;
            return oldValue;
        }

        return removeStash(key, defaultValue);
    }

    int removeStash(int key, int defaultValue){
        int[] keyTable = this.keyTable;
        for(int i = capacity, n = i + stashSize; i < n; i++){
            if(key == keyTable[i]){
                int oldValue = valueTable[i];
                removeStashIndex(i);
                size--;
                return oldValue;
            }
        }
        return defaultValue;
    }

    void removeStashIndex(int index){
        // If the removed location was not last, move the last tuple to the removed location.
        stashSize--;
        int lastIndex = capacity + stashSize;
        if(index < lastIndex){
            keyTable[index] = keyTable[lastIndex];
            valueTable[index] = valueTable[lastIndex];
        }
    }

    /** Returns true if the map is empty. */
    public boolean isEmpty(){
        return size == 0;
    }

    /**
     * Reduces the size of the backing arrays to be the specified capacity or less. If the capacity is already less, nothing is
     * done. If the map contains more items than the specified capacity, the next highest power of two capacity is used instead.
     */
    public void shrink(int maximumCapacity){
        if(maximumCapacity < 0) throw new IllegalArgumentException("maximumCapacity must be >= 0: " + maximumCapacity);
        if(size > maximumCapacity) maximumCapacity = size;
        if(capacity <= maximumCapacity) return;
        maximumCapacity = Mathf.nextPowerOfTwo(maximumCapacity);
        resize(maximumCapacity);
    }

    /** Clears the map and reduces the size of the backing arrays to be the specified capacity if they are larger. */
    public void clear(int maximumCapacity){
        if(capacity <= maximumCapacity){
            clear();
            return;
        }
        hasZeroValue = false;
        size = 0;
        resize(maximumCapacity);
    }

    public void clear(){
        if(size == 0) return;
        int[] keyTable = this.keyTable;
        for(int i = capacity + stashSize; i-- > 0; )
            keyTable[i] = EMPTY;
        size = 0;
        stashSize = 0;
        hasZeroValue = false;
    }

    /**
     * Returns true if the specified value is in the map. Note this traverses the entire map and compares every value, which may be
     * an expensive operation.
     */
    public boolean containsValue(int value){
        if(hasZeroValue && zeroValue == value) return true;
        int[] keyTable = this.keyTable, valueTable = this.valueTable;
        for(int i = capacity + stashSize; i-- > 0; )
            if(keyTable[i] != 0 && valueTable[i] == value) return true;
        return false;
    }

    public boolean containsKey(int key){
        if(key == 0) return hasZeroValue;
        int index = key & mask;
        if(keyTable[index] != key){
            index = hash2(key);
            if(keyTable[index] != key){
                index = hash3(key);
                if(keyTable[index] != key) return containsKeyStash(key);
            }
        }
        return true;
    }

    private boolean containsKeyStash(int key){
        int[] keyTable = this.keyTable;
        for(int i = capacity, n = i + stashSize; i < n; i++)
            if(key == keyTable[i]) return true;
        return false;
    }

    /**
     * Returns the key for the specified value, or null if it is not in the map. Note this traverses the entire map and compares
     * every value, which may be an expensive operation.
     */
    public int findKey(int value, int notFound){
        if(hasZeroValue && zeroValue == value) return 0;
        int[] keyTable = this.keyTable, valueTable = this.valueTable;
        for(int i = capacity + stashSize; i-- > 0; )
            if(keyTable[i] != 0 && valueTable[i] == value) return keyTable[i];
        return notFound;
    }

    /**
     * Increases the size of the backing array to accommodate the specified number of additional items. Useful before adding many
     * items to avoid multiple backing array resizes.
     */
    public void ensureCapacity(int additionalCapacity){
        if(additionalCapacity < 0)
            throw new IllegalArgumentException("additionalCapacity must be >= 0: " + additionalCapacity);
        int sizeNeeded = size + additionalCapacity;
        if(sizeNeeded >= threshold) resize(Mathf.nextPowerOfTwo((int)Math.ceil(sizeNeeded / loadFactor)));
    }

    private void resize(int newSize){
        int oldEndIndex = capacity + stashSize;

        capacity = newSize;
        threshold = (int)(newSize * loadFactor);
        mask = newSize - 1;
        hashShift = 31 - Integer.numberOfTrailingZeros(newSize);
        stashCapacity = Math.max(3, (int)Math.ceil(Math.log(newSize)) * 2);
        pushIterations = Math.max(Math.min(newSize, 8), (int)Math.sqrt(newSize) / 8);

        int[] oldKeyTable = keyTable;
        int[] oldValueTable = valueTable;

        keyTable = new int[newSize + stashCapacity];
        valueTable = new int[newSize + stashCapacity];

        int oldSize = size;
        size = hasZeroValue ? 1 : 0;
        stashSize = 0;
        if(oldSize > 0){
            for(int i = 0; i < oldEndIndex; i++){
                int key = oldKeyTable[i];
                if(key != EMPTY) putResize(key, oldValueTable[i]);
            }
        }
    }

    private int hash2(int h){
        h *= PRIME2;
        return (h ^ h >>> hashShift) & mask;
    }

    private int hash3(int h){
        h *= PRIME3;
        return (h ^ h >>> hashShift) & mask;
    }

    public int hashCode(){
        int h = 0;
        if(hasZeroValue){
            h += Float.floatToIntBits(zeroValue);
        }
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = 0, n = capacity + stashSize; i < n; i++){
            int key = keyTable[i];
            if(key != EMPTY){
                h += key * 31;

                int value = valueTable[i];
                h += value;
            }
        }
        return h;
    }

    public boolean equals(Object obj){
        if(obj == this) return true;
        if(!(obj instanceof IntIntMap)) return false;
        IntIntMap other = (IntIntMap)obj;
        if(other.size != size) return false;
        if(other.hasZeroValue != hasZeroValue) return false;
        if(hasZeroValue && other.zeroValue != zeroValue){
            return false;
        }
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = 0, n = capacity + stashSize; i < n; i++){
            int key = keyTable[i];
            if(key != EMPTY){
                int otherValue = other.get(key, 0);
                if(otherValue == 0 && !other.containsKey(key)) return false;
                int value = valueTable[i];
                if(otherValue != value) return false;
            }
        }
        return true;
    }

    public String toString(){
        if(size == 0) return "{}";
        StringBuilder buffer = new StringBuilder(32);
        buffer.append('{');
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        int i = keyTable.length;
        if(hasZeroValue){
            buffer.append("0=");
            buffer.append(zeroValue);
        }else{
            while(i-- > 0){
                int key = keyTable[i];
                if(key == EMPTY) continue;
                buffer.append(key);
                buffer.append('=');
                buffer.append(valueTable[i]);
                break;
            }
        }
        while(i-- > 0){
            int key = keyTable[i];
            if(key == EMPTY) continue;
            buffer.append(", ");
            buffer.append(key);
            buffer.append('=');
            buffer.append(valueTable[i]);
        }
        buffer.append('}');
        return buffer.toString();
    }

    public Iterator<Entry> iterator(){
        return entries();
    }

    /**
     * Returns an iterator for the entries in the map. Remove is supported. Note that the same iterator instance is returned each
     * time this method is called. Use the {@link Entries} constructor for nested or multithreaded iteration.
     */
    public Entries entries(){
        if(entries1 == null){
            entries1 = new Entries(this);
            entries2 = new Entries(this);
        }
        if(!entries1.valid){
            entries1.reset();
            entries1.valid = true;
            entries2.valid = false;
            return entries1;
        }
        entries2.reset();
        entries2.valid = true;
        entries1.valid = false;
        return entries2;
    }

    /**
     * Returns an iterator for the values in the map. Remove is supported. Note that the same iterator instance is returned each
     * time this method is called. Use the {@link Entries} constructor for nested or multithreaded iteration.
     */
    public Values values(){
        if(values1 == null){
            values1 = new Values(this);
            values2 = new Values(this);
        }
        if(!values1.valid){
            values1.reset();
            values1.valid = true;
            values2.valid = false;
            return values1;
        }
        values2.reset();
        values2.valid = true;
        values1.valid = false;
        return values2;
    }

    /**
     * Returns an iterator for the keys in the map. Remove is supported. Note that the same iterator instance is returned each time
     * this method is called. Use the {@link Entries} constructor for nested or multithreaded iteration.
     */
    public Keys keys(){
        if(keys1 == null){
            keys1 = new Keys(this);
            keys2 = new Keys(this);
        }
        if(!keys1.valid){
            keys1.reset();
            keys1.valid = true;
            keys2.valid = false;
            return keys1;
        }
        keys2.reset();
        keys2.valid = true;
        keys1.valid = false;
        return keys2;
    }

    public static class Entry{
        public int key;
        public int value;

        public String toString(){
            return key + "=" + value;
        }
    }

    private static class MapIterator{
        static final int INDEX_ILLEGAL = -2;
        static final int INDEX_ZERO = -1;
        final IntIntMap map;
        public boolean hasNext;
        int nextIndex, currentIndex;
        boolean valid = true;

        public MapIterator(IntIntMap map){
            this.map = map;
            reset();
        }

        public void reset(){
            currentIndex = INDEX_ILLEGAL;
            nextIndex = INDEX_ZERO;
            if(map.hasZeroValue)
                hasNext = true;
            else
                findNextIndex();
        }

        void findNextIndex(){
            hasNext = false;
            int[] keyTable = map.keyTable;
            for(int n = map.capacity + map.stashSize; ++nextIndex < n; ){
                if(keyTable[nextIndex] != EMPTY){
                    hasNext = true;
                    break;
                }
            }
        }

        public void remove(){
            if(currentIndex == INDEX_ZERO && map.hasZeroValue){
                map.hasZeroValue = false;
            }else if(currentIndex < 0){
                throw new IllegalStateException("next must be called before remove.");
            }else if(currentIndex >= map.capacity){
                map.removeStashIndex(currentIndex);
                nextIndex = currentIndex - 1;
                findNextIndex();
            }else{
                map.keyTable[currentIndex] = EMPTY;
            }
            currentIndex = INDEX_ILLEGAL;
            map.size--;
        }
    }

    public static class Entries extends MapIterator implements Iterable<Entry>, Iterator<Entry>{
        private Entry entry = new Entry();

        public Entries(IntIntMap map){
            super(map);
        }

        /** Note the same entry instance is returned each time this method is called. */
        public Entry next(){
            if(!hasNext) throw new NoSuchElementException();
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            int[] keyTable = map.keyTable;
            if(nextIndex == INDEX_ZERO){
                entry.key = 0;
                entry.value = map.zeroValue;
            }else{
                entry.key = keyTable[nextIndex];
                entry.value = map.valueTable[nextIndex];
            }
            currentIndex = nextIndex;
            findNextIndex();
            return entry;
        }

        public boolean hasNext(){
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            return hasNext;
        }

        public Iterator<Entry> iterator(){
            return this;
        }

        public void remove(){
            super.remove();
        }
    }

    public static class Values extends MapIterator{
        public Values(IntIntMap map){
            super(map);
        }

        public boolean hasNext(){
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            return hasNext;
        }

        public int next(){
            if(!hasNext) throw new NoSuchElementException();
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            int value;
            if(nextIndex == INDEX_ZERO)
                value = map.zeroValue;
            else
                value = map.valueTable[nextIndex];
            currentIndex = nextIndex;
            findNextIndex();
            return value;
        }

        /** Returns a new array containing the remaining values. */
        public IntSeq toArray(){
            IntSeq array = new IntSeq(true, map.size);
            while(hasNext)
                array.add(next());
            return array;
        }
    }

    public static class Keys extends MapIterator{
        public Keys(IntIntMap map){
            super(map);
        }

        public boolean hasNext(){
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            return hasNext;
        }

        public int next(){
            if(!hasNext) throw new NoSuchElementException();
            if(!valid) throw new ArcRuntimeException("#iterator() cannot be used nested.");
            int key = nextIndex == INDEX_ZERO ? 0 : map.keyTable[nextIndex];
            currentIndex = nextIndex;
            findNextIndex();
            return key;
        }

        /** Returns a new array containing the remaining keys. */
        public IntSeq toArray(){
            IntSeq array = new IntSeq(true, map.size);
            while(hasNext)
                array.add(next());
            return array;
        }
    }
}
