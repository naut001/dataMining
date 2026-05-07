# 🔍 Locally Periodic Pattern Mining (LPPM)

> **Môn học:** Data Mining  
> **Chủ đề:** Khai thác mẫu định kỳ cục bộ — so sánh hiệu năng 3 thuật toán LPPM  
> **Nguồn thuật toán:** [SPMF Library](https://www.philippe-fournier-viger.com/spmf/) (Philippe Fournier-Viger)

---

## 📌 Tổng quan

Project này hiện thực và so sánh **3 thuật toán khai thác mẫu định kỳ cục bộ (LPPM)** trên các tập dữ liệu giao dịch thực tế:

| Thuật toán | Cấu trúc dữ liệu | Cách duyệt |
|---|---|---|
| **LPP-Growth** | LPPTree (nén dữ liệu) | Pattern-Growth (Top-down) |
| **LPPM-Breadth** | Vertical DB + BitSet | BFS (Apriori-like) |
| **LPPM-Depth** | Vertical DB + BitSet | DFS (Equivalence Class - Eclat-like) |

---

## 🏛️ Kiến trúc mã nguồn

```
dataMining/
├── src/
│   ├── algorithms/
│   │   ├── lppgrowth/          # AlgoLPPGrowth.java + LPPTree structures
│   │   └── lppm/               # AlgoLPPMBreadth1/2.java, AlgoLPPMDepth1/2.java
│   ├── experiment/
│   │   ├── ExperimentLPPGrowth.java
│   │   ├── ExperimentLPPMBreadth.java
│   │   ├── ExperimentLPPMDepth.java
│   │   ├── ExperimentResult.java    # Data class → CSV output
│   │   └── RunAllExperiments.java   # 🚀 Master: chạy 18 kịch bản tự động
│   ├── tools/
│   │   ├── MemoryLogger.java        # Utility để đo memory usage
│   │   ├── dataset_converter/
│   │   ├── dataset_generator/
│   │   ├── dataset_stats/
│   │   ├── other_dataset_tools/
│   │   └── resultConverter/
│   ├── data/                        # (Download datasets tại đây - xem bên dưới)
│   └── LICENSE_AGREEMENT_GPL3.txt   # GPL v3 license từ SPMF
├── bin/                             # Compiled .class files (tự động tạo khi compile)
├── outputs/
│   ├── summary_all_experiments.csv  # Tổng hợp kết quả benchmark
│   └── *_stats.txt                  # Stats từng kịch bản
└── REPORT.md                        # Báo cáo chi tiết
```

---

## ⚙️ Các tham số thực nghiệm

Mỗi thuật toán được chạy trên **2 dataset × 3 giá trị maxPer = 18 kịch bản**:

| Tham số | Giá trị |
|---|---|
| `maxPer` | 10%, 20%, 30% (của tổng số transactions) |
| `minDur` | 50 |
| `maxSoPer` | 3 |
| Dataset 1 | `retail` — 88,162 giao dịch |
| Dataset 2 | `kosarak` — 990,002 giao dịch |

---

## 🚀 Hướng dẫn cài đặt & chạy

### 1. Tải dataset (bắt buộc — không có trong repo do kích thước lớn)

```bash
# Dataset retail (~3MB)
curl -o src/data/retail.txt http://www.philippe-fournier-viger.com/spmf/datasets/retail.txt

# Dataset kosarak (~30MB)
curl -o src/data/kosarak.dat.txt http://www.philippe-fournier-viger.com/spmf/datasets/kosarak.dat.txt
```

Hoặc tải thủ công và đặt vào `src/data/`.

### 2. Biên dịch

**Trên Linux/Mac:**
```bash
mkdir -p bin
javac -encoding UTF-8 -d bin $(find src -name "*.java")
```

**Trên Windows (PowerShell):**
```powershell
mkdir bin -Force
javac -d bin -sourcepath src src/experiment/RunAllExperiments.java src/experiment/*.java src/algorithms/lppgrowth/*.java src/algorithms/lppm/*.java src/tools/MemoryLogger.java
```

### 3. Chạy thực nghiệm

**Chạy từng thuật toán đơn lẻ:**
```bash
java -Xmx2g -cp bin experiment.ExperimentLPPGrowth
java -Xmx2g -cp bin experiment.ExperimentLPPMBreadth
java -Xmx2g -cp bin experiment.ExperimentLPPMDepth
```

**Chạy toàn bộ 18 kịch bản benchmark:**
```bash
java -Xmx4g -cp bin experiment.RunAllExperiments
```
> 💡 `-Xmx4g` để quan sát hiện tượng `OutOfMemoryError` của LPPM-Breadth/Depth trên kosarak.

---

## 📊 Kết quả

Kết quả tổng hợp được lưu tại [`outputs/summary_all_experiments.csv`](outputs/summary_all_experiments.csv).

### Nhận xét nổi bật

- ✅ **LPP-Growth** — nhanh nhất, tiêu thụ RAM ít nhất (~190MB ngay cả trên kosarak 990K transactions)
- ❌ **LPPM-Breadth** — `OutOfMemoryError` trên kosarak do BitSet ngốn hàng GB RAM
- ❌ **LPPM-Depth** — cũng gặp tử huyệt RAM trên kosarak vì "gánh nặng bẩm sinh" của Vertical Database

---

## 📜 Giấy phép

Mã thuật toán gốc từ SPMF được cấp phép theo **GPL v3** — xem [`src/LICENSE_AGREEMENT_GPL3.txt`](src/LICENSE_AGREEMENT_GPL3.txt).
