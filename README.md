# 🔍 Locally Periodic Pattern Mining (LPPM)

> **Môn học:** Data Mining  
> **Chủ đề:** Khai thác mẫu định kỳ cục bộ — so sánh hiệu năng 3 thuật toán LPPM  
> **Nguồn thuật toán:** [SPMF Library](https://www.philippe-fournier-viger.com/spmf/) (Philippe Fournier-Viger)

---

## 📌 Tổng Quan

Project này hiện thực và so sánh **3 thuật toán khai thác mẫu định kỳ cục bộ (LPPM)** trên các tập dữ liệu giao dịch thực tế:

| Thuật toán | Cấu trúc dữ liệu | Cách duyệt | Hiệu năng |
|---|---|---|---|
| **LPP-Growth** | LPPTree (nén dữ liệu) | Pattern-Growth (Top-down) | ✅ Nhanh nhất |
| **LPPM-Breadth** | Vertical DB + BitSet | BFS (Apriori-like) | ⚠️ OutOfMemory |
| **LPPM-Depth** | Vertical DB + BitSet | DFS (Equivalence Class - Eclat-like) | ⚠️ OutOfMemory |

**Ngoài ra:** Project có **phiên bản song song hóa (parallel)** của cả 3 thuật toán sử dụng `ExecutorService`.

📖 **[Xem báo cáo chi tiết tại REPORT.md](REPORT.md)**

---

## 🚀 Quick Start

### 1. Tải Dataset (Bắt Buộc)

```bash
# Dataset retail (~3MB)
curl -o src/data/retail.txt http://www.philippe-fournier-viger.com/spmf/datasets/retail.txt

# Dataset kosarak (~30MB)
curl -o src/data/kosarak.dat.txt http://www.philippe-fournier-viger.com/spmf/datasets/kosarak.dat.txt
```

### 2. Biên Dịch

**Windows (PowerShell):**
```powershell
mkdir bin -Force
javac -encoding UTF-8 -d bin -sourcepath src (Get-ChildItem -Path src -Recurse -Filter "*.java").FullName
```

**Linux/Mac:**
```bash
mkdir -p bin
javac -encoding UTF-8 -d bin $(find src -name "*.java")
```

### 3. Chạy Thực Nghiệm

**Chạy toàn bộ 18 kịch bản benchmark (sequential):**
```bash
java -Xmx4g -cp bin experiment.RunAllExperiments
```

**Chạy toàn bộ 36 runs (sequential + parallel):**
```bash
java -Xmx4g -cp bin experiment.RunAllParallelExperiments
```

**Chạy từng thuật toán đơn lẻ:**
```bash
# LPP-Growth
java -Xmx2g -cp bin experiment.ExperimentLPPGrowth

# LPPM-Breadth
java -Xmx2g -cp bin experiment.ExperimentLPPMBreadth

# LPPM-Depth
java -Xmx2g -cp bin experiment.ExperimentLPPMDepth
```

> 💡 **Lưu ý:** Dùng `-Xmx4g` để quan sát hiện tượng `OutOfMemoryError` của LPPM-Breadth/Depth trên kosarak.

---

## 📊 Kết Quả

Kết quả tổng hợp được lưu tại:
- **Sequential:** [`outputs/summary_all_experiments.csv`](outputs/summary_all_experiments.csv)
- **Parallel:** [`outputs_parallel/speedup_summary.csv`](outputs_parallel/speedup_summary.csv)

### Nhận Xét Nổi Bật

| Thuật toán | Retail (88K tx) | Kosarak (990K tx) |
|-----------|-----------------|-------------------|
| **LPP-Growth** | ✅ ~50-100ms, ~50MB | ✅ ~190MB RAM |
| **LPPM-Breadth** | ✅ ~100-200ms, ~100MB | ❌ OutOfMemory |
| **LPPM-Depth** | ✅ ~150-300ms, ~80MB | ❌ OutOfMemory |

**Parallel Speedup:** 1.5x - 3.0x (tùy số CPU cores)

---

## 🏛️ Cấu Trúc Project

```
dataMiningF/
├── src/
│   ├── algorithms/
│   │   ├── lppgrowth/          # LPP-Growth (sequential + parallel)
│   │   └── lppm/               # LPPM-Breadth & LPPM-Depth (sequential + parallel)
│   ├── experiment/             # Benchmark wrappers & master runners
│   └── tools/                  # MemoryLogger utility
├── bin/                        # Compiled .class files
├── outputs/                    # Sequential results
├── outputs_parallel/           # Parallel results
├── README.md                   # This file
└── REPORT.md                   # 📖 Báo cáo chi tiết đầy đủ
```

---

## 📚 Tài Liệu

- **[REPORT.md](REPORT.md)** - Báo cáo chi tiết đầy đủ về:
  - Mô tả thuật toán chi tiết
  - Kiến trúc hệ thống
  - Thiết kế thực nghiệm
  - Kết quả và phân tích
  - Parallel implementation details
  - Design patterns

---

## 📜 Giấy Phép

Mã thuật toán gốc từ SPMF được cấp phép theo **GPL v3** — xem [`src/LICENSE_AGREEMENT_GPL3.txt`](src/LICENSE_AGREEMENT_GPL3.txt).

---

**Cập nhật:** 2026-05-14
