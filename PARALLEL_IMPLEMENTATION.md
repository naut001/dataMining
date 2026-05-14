# 🚀 Parallel Implementation of LPPM Algorithms

**Ngày hoàn thành:** 2026-05-14  
**Mục đích:** Viết phiên bản song song (parallel) của 3 thuật toán LPPM sử dụng `java.util.concurrent.ExecutorService`

---

## ✅ Tổng quan

Đã hoàn thành việc viết **3 thuật toán parallel** và **4 experiment classes** để benchmark và so sánh hiệu năng.

### 📊 Danh sách files đã tạo

| File | Dòng code | Mô tả |
|------|-----------|-------|
| `AlgoLPPGrowth_Parallel.java` | ~700 | LPP-Growth parallel - song song hóa Header Table Loop |
| `AlgoLPPMDepth2_Parallel.java` | ~550 | LPPM-Depth parallel - song song hóa ở Level 1 |
| `AlgoLPPMBreadth2_Parallel.java` | ~650 | LPPM-Breadth parallel - level-wise sync + prefix groups |
| `ExperimentLPPGrowth_Parallel.java` | ~150 | Experiment wrapper cho LPP-Growth |
| `ExperimentLPPMDepth_Parallel.java` | ~150 | Experiment wrapper cho LPPM-Depth |
| `ExperimentLPPMBreadth_Parallel.java` | ~150 | Experiment wrapper cho LPPM-Breadth |
| `RunAllExperiments_Parallel.java` | ~150 | Master runner - chạy tất cả experiments |
| **Tổng** | **~2,500** | **7 files mới** |

---

## 🎯 Chiến lược song song hóa

### 1️⃣ **AlgoLPPGrowth_Parallel.java**

**Chiến lược:** Song song hóa vòng lặp Header Table Loop

```java
// ===== SONG SONG HÓA: Mỗi item trong header table = 1 task độc lập =====
ExecutorService executor = Executors.newFixedThreadPool(numThreads);

for (int i = tree.headerList.size() - 1; i >= 0; i--) {
    final Integer item = tree.headerList.get(i);
    
    executor.submit(() -> {
        // Mỗi thread xử lý 1 item: tạo conditional tree + đệ quy
        processHeaderItem(item, tree, mapTimeIntervals);
    });
}

// Đợi tất cả threads hoàn thành
executor.shutdown();
executor.awaitTermination(60, TimeUnit.MINUTES);
```

**Đặc điểm:**
- ✅ Mỗi item trong header table độc lập hoàn toàn
- ✅ Không cần synchronization giữa các nhánh
- ✅ Đệ quy chạy tuần tự trong mỗi nhánh (tránh overhead)
- ⚠️ Memory: Mỗi thread tạo conditional tree riêng

---

### 2️⃣ **AlgoLPPMDepth2_Parallel.java**

**Chiến lược:** Song song hóa ở Level 1 (Root level) - DFS

```java
// ===== SONG SONG HÓA: Mỗi frequent item ở level 1 = 1 nhánh DFS độc lập =====
ExecutorService executor = Executors.newFixedThreadPool(numThreads);

for (int i = 0; i < lpp1.size() - 1; i++) {
    final Integer itemI = lpp1.get(i);
    final BitSet tsSetI = (BitSet) mapItemTS.get(itemI).clone();
    
    executor.submit(() -> {
        // Mỗi thread thực hiện toàn bộ DFS cho nhánh của nó
        processRootItem(itemI, tsSetI, lpp1, mapItemTS, i);
    });
}

executor.shutdown();
executor.awaitTermination(60, TimeUnit.MINUTES);
```

**Đặc điểm:**
- ✅ Chỉ song song hóa ở level 1 (tránh quá nhiều threads)
- ✅ Mỗi nhánh DFS hoàn toàn độc lập
- ✅ Không cần synchronization giữa các nhánh
- ⚠️ Load imbalance: Một số nhánh sâu hơn

---

### 3️⃣ **AlgoLPPMBreadth2_Parallel.java**

**Chiến lược:** Level-wise synchronization + song song hóa prefix groups

```java
// ===== SONG SONG HÓA: Chia các prefix groups cho các threads =====
List<Callable<LinkedHashMap<int[], ArrayList<Integer>>>> tasks = new ArrayList<>();

for (Map.Entry<int[], ArrayList<Integer>> entry : combinationMap.entrySet()) {
    final int[] prefixKey = entry.getKey();
    final ArrayList<Integer> suffixItems = entry.getValue();
    
    Callable<LinkedHashMap<int[], ArrayList<Integer>>> task = () -> {
        return processPrefixGroup(prefixKey, suffixItems, mapItemTS);
    };
    
    tasks.add(task);
}

// ===== LEVEL-WISE SYNCHRONIZATION: Đợi tất cả prefix groups hoàn thành =====
executor.invokeAll(tasks).forEach(future -> {
    LinkedHashMap<int[], ArrayList<Integer>> result = future.get();
    newCombinationMap.putAll(result);
});
```

**Đặc điểm:**
- ✅ Level-wise sync: Tất cả threads phải xong level k trước khi sang k+1
- ✅ Dùng `invokeAll()` để đảm bảo synchronization
- ✅ Mỗi prefix group độc lập trong cùng level
- ⚠️ Synchronization overhead ở mỗi level

---

## 🔒 Thread-Safety Implementation

### 1. **AtomicInteger cho counters**

```java
// ❌ KHÔNG AN TOÀN
private int itemsetCount = 0;
itemsetCount++;

// ✅ AN TOÀN
private AtomicInteger itemsetCount = new AtomicInteger(0);
itemsetCount.incrementAndGet();
```

### 2. **ConcurrentLinkedQueue cho kết quả**

```java
// Queue thread-safe để lưu kết quả
private ConcurrentLinkedQueue<String> resultQueue = new ConcurrentLinkedQueue<>();

// Threads add vào queue
resultQueue.add(buffer.toString());

// Sau khi tất cả threads xong, ghi ra file
for (String result : resultQueue) {
    writer.write(result);
    writer.newLine();
}
```

### 3. **Synchronized cho patterns (nếu lưu vào memory)**

```java
// Synchronized để tránh race condition
synchronized (patterns) {
    patterns.addItemset(itemsetObj, itemsetLength);
}
```

### 4. **Shutdown pattern**

```java
executor.shutdown();
try {
    if (!executor.awaitTermination(60, TimeUnit.MINUTES)) {
        executor.shutdownNow();
    }
} catch (InterruptedException e) {
    executor.shutdownNow();
    Thread.currentThread().interrupt();
}
// Đo thời gian SAU KHI tất cả threads xong
endTime = System.currentTimeMillis();
```

---

## 📁 Cấu trúc thư mục

```
dataMiningF/
├── src/
│   ├── algorithms/
│   │   ├── lppgrowth/
│   │   │   ├── AlgoLPPGrowth.java              # Sequential (original)
│   │   │   └── AlgoLPPGrowth_Parallel.java     # ✨ NEW - Parallel
│   │   └── lppm/
│   │       ├── AlgoLPPMBreadth2.java           # Sequential (original)
│   │       ├── AlgoLPPMBreadth2_Parallel.java  # ✨ NEW - Parallel
│   │       ├── AlgoLPPMDepth2.java             # Sequential (original)
│   │       └── AlgoLPPMDepth2_Parallel.java    # ✨ NEW - Parallel
│   └── experiment/
│       ├── ExperimentLPPGrowth.java                    # Sequential
│       ├── ExperimentLPPGrowth_Parallel.java           # ✨ NEW
│       ├── ExperimentLPPMBreadth.java                  # Sequential
│       ├── ExperimentLPPMBreadth_Parallel.java         # ✨ NEW
│       ├── ExperimentLPPMDepth.java                    # Sequential
│       ├── ExperimentLPPMDepth_Parallel.java           # ✨ NEW
│       ├── RunAllExperiments.java                      # Sequential
│       └── RunAllExperiments_Parallel.java             # ✨ NEW
├── outputs/                    # Sequential results
└── outputs_parallel/           # ✨ NEW - Parallel results
```

---

## 🚀 Cách chạy

### 1. Chạy từng thuật toán riêng lẻ

```bash
# LPP-Growth Parallel (mặc định dùng tất cả processors)
java -Xmx4g -cp bin experiment.ExperimentLPPGrowth_Parallel

# LPPM-Depth Parallel
java -Xmx4g -cp bin experiment.ExperimentLPPMDepth_Parallel

# LPPM-Breadth Parallel
java -Xmx4g -cp bin experiment.ExperimentLPPMBreadth_Parallel
```

### 2. Chạy tất cả experiments với nhiều thread counts

```bash
# Chạy với 1, 2, 4, và tất cả processors
java -Xmx4g -cp bin experiment.RunAllExperiments_Parallel
```

**Output:**
- Kết quả lưu tại: `outputs_parallel/`
- CSV tổng hợp: `outputs_parallel/summary_parallel_experiments.csv`
- Speedup analysis được in ra console

---

## 📊 Kết quả mong đợi

### Speedup dự kiến (trên CPU 4 cores):

| Thuật toán | 1 thread | 2 threads | 4 threads | Speedup (4T) |
|------------|----------|-----------|-----------|--------------|
| LPP-Growth | Baseline | ~1.7x | ~3.0x | **3.0x** |
| LPPM-Depth | Baseline | ~1.8x | ~3.2x | **3.2x** |
| LPPM-Breadth | Baseline | ~1.5x | ~2.5x | **2.5x** (sync overhead) |

**Lưu ý:**
- Speedup thực tế phụ thuộc vào:
  - Số lượng cores CPU
  - Kích thước dataset
  - Load balancing
  - Synchronization overhead

---

## ⚠️ Lưu ý quan trọng

### 1. **Memory Usage**
- Parallel version tiêu thụ **nhiều RAM hơn** sequential
- Mỗi thread có data structures riêng
- Khuyến nghị: `-Xmx4g` hoặc cao hơn

### 2. **Thread Count**
- Không phải càng nhiều threads càng tốt
- Optimal: Số threads = Số cores CPU
- Quá nhiều threads → Context switching overhead

### 3. **Dataset Size**
- Dataset nhỏ (retail): Parallel có thể **chậm hơn** do overhead
- Dataset lớn (kosarak): Parallel **nhanh hơn** rõ rệt

### 4. **Correctness**
- Kết quả phải **giống hệt** bản sequential
- Số patterns phải bằng nhau
- Verify bằng cách so sánh output files

---

## ✅ Checklist hoàn thành

- [x] AlgoLPPGrowth_Parallel.java - Song song hóa Header Table Loop
- [x] AlgoLPPMDepth2_Parallel.java - Song song hóa ở Level 1
- [x] AlgoLPPMBreadth2_Parallel.java - Level-wise sync + prefix groups
- [x] Thread-safety: AtomicInteger, ConcurrentLinkedQueue, synchronized
- [x] Shutdown pattern: executor.shutdown() + awaitTermination()
- [x] ExperimentLPPGrowth_Parallel.java
- [x] ExperimentLPPMDepth_Parallel.java
- [x] ExperimentLPPMBreadth_Parallel.java
- [x] RunAllExperiments_Parallel.java với speedup analysis
- [x] Compile thành công tất cả files
- [x] Tài liệu chi tiết

---

## 🎓 Kết luận

Đã hoàn thành việc viết **3 thuật toán LPPM parallel** với:

✅ **Chiến lược song song hóa đúng đắn** cho từng thuật toán  
✅ **Thread-safety đầy đủ** (AtomicInteger, ConcurrentLinkedQueue, synchronized)  
✅ **Shutdown pattern chuẩn** (awaitTermination trước khi đo thời gian)  
✅ **Giữ nguyên core logic** - chỉ song song hóa vòng lặp  
✅ **Experiment classes đầy đủ** để benchmark và so sánh  
✅ **Code compile thành công** - sẵn sàng để test  

**Bước tiếp theo:**
1. Chạy experiments trên retail dataset
2. So sánh kết quả với bản sequential (verify correctness)
3. Đo speedup với các thread counts khác nhau
4. Tạo biểu đồ so sánh hiệu năng

---

**Tác giả:** Parallel version 2026  
**Dựa trên:** SPMF Library (Philippe Fournier-Viger)  
**License:** GPL v3
