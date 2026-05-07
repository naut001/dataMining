# Báo Cáo Thực Nghiệm: Khai Thác Mẫu Định Kỳ Cục Bộ (Locally Periodic Pattern Mining - LPPM)

**Môn học:** Data Mining  
**Bài tập:** Tách module thuật toán, viết test độc lập và so sánh hiệu năng  
**Bộ thuật toán:** LPP-Growth, LPPM-Breadth, LPPM-Depth (Từ thư viện SPMF gốc).

---

## 🔗 Tái lập thực nghiệm (Reproducibility)

Để Giảng viên có thể tải và chạy lại kết quả thực tế, xin vui lòng tải về các file dữ liệu chuẩn từ kho máy chủ SPMF và chép vào thư mục `src/data/`:

1. **Dataset retail (88,162 giao dịch)**
   - Link tải: [retail.txt](http://www.philippe-fournier-viger.com/spmf/datasets/retail.txt)
   - Lưu vào: `src/data/retail.txt`

2. **Dataset kosarak (990,002 giao dịch)**
   - Link tải: [kosarak.dat.txt](http://www.philippe-fournier-viger.com/spmf/datasets/kosarak.dat.txt)
   - Lưu vào: `src/data/kosarak.dat.txt`

---

## 🏛️ Bố cục mã nguồn (Architecture)

Toàn bộ project đã được cấu trúc gọn gàng, loại bỏ 100% các file rác và giao diện GUI không cần thiết từ SPMF gốc. Hệ thống được chia làm **2 nhóm độc lập**, tuân thủ nguyên tắc Single Responsibility (trách nhiệm đơn lẻ):

### Nhóm 1: Các class Giải Thuật (Original Algorithm Classes)
Đường dẫn: `src/ca/pfv/spmf/algorithms/frequentpatterns/`

Các file này được giữ **nguyên thủy 100%**, hoàn toàn không chứa hàm `main()`, chỉ đóng vai trò thư viện xử lý logic sinh mẫu:
- **LPP-Growth:** `AlgoLPPGrowth.java` (Sử dụng cấu trúc LPPTree nén dữ liệu).
- **LPPM-Breadth:** `AlgoLPPMBreadth1.java` và `AlgoLPPMBreadth2.java` (bản tối ưu dùng SPM - Share Prefix).
- **LPPM-Depth:** `AlgoLPPMDepth1.java` và `AlgoLPPMDepth2.java` (bản baseline chuẩn).

### Nhóm 2: Các class Thực Nghiệm (Experiment Classes)
Đường dẫn: `src/ca/pfv/spmf/experiment/`

Lớp bao bọc (wrapper logic) và chạy thuật toán. Mỗi thuật toán có một file test độc lập chứa hàm `main()` để cấu hình tham số, đọc DB, và lưu các thông số thống kê (runtime, max memory, frequent pattern count) ra file CSV:
- **`ExperimentLPPGrowth.java`**: Chạy thực nghiệm đọc file và đo lường LPP-Growth.
- **`ExperimentLPPMBreadth.java`**: Chạy đo lường và handle `OutOfMemoryError` đối với LPP-Breadth2.
- **`ExperimentLPPMDepth.java`**: Phụ trách LPP-Depth2.
- **`RunAllExperiments.java`**: Chương trình Master, tự động chạy toàn bộ 18 kịch bản kết hợp (3 Algorithms × 2 Datasets × 3 maxPer) và tổng hợp kết quả (được lưu tại `outputs/summary_all_experiments.csv`).
- **`ExperimentResult.java`**: Data class đóng gói kết quả để xuất ra CSV chuẩn `Locale.US` để vẽ biểu đồ dễ dàng.

---

## 🧠 Mô tả giải thuật (Mối tương quan với Code)

Bài toán Locally Periodic Pattern Mining (LPPM) yêu cầu tìm các mẫu không cần tuần hoàn xuất hiện trong toàn bộ DB như lý thuyết mẫu định kỳ cổ điển, mà chỉ cần **xuất hiện liên tục** trong một khoảng thời gian độ dài tối thiểu `minDur` mà khoảng cách `gap` giữa 2 lần xuất hiện không vượt quá mức định kỳ `maxPer` (cộng thêm yếu tố nhiễu `maxSoPer`). 

3 thuật toán sinh mẫu giải quyết bài toán bằng 3 cấu trúc dữ liệu khác nhau (thể hiện rõ trong source code):

### 1. Thuật toán LPP-Growth (Pattern-Growth Approach)
- **Đại diện chạy:** `ExperimentLPPGrowth` $\rightarrow$ gọi class `AlgoLPPGrowth`
- **Cơ chế code:** 
  - Đọc file dọc 1 lần để xây dựng **LPPTree** (tương tự FP-Tree) kết hợp danh sách `TimeIntervals` (các khoảng thời gian xuất hiện hợp lệ của từng node).
  - Thuật toán `mine()` duyệt cây theo cơ chế bottom-up, thiết lập các Conditional Pattern Base và nối dần độ dài item mà không cần quá trình sinh tập ứng viên cực nhọc.
- **Thực tế benchmark:** Tốc độ nhanh nhất và tốn cực kỳ ít khoảng trống RAM nhờ "tree path sharing". Dễ dàng pass bài test siêu cấu trúc `kosarak` với 190MB RAM (trong khi hai thuật toán còn lại đều lỗi `OutOfMemory`).

### 2. Thuật toán LPPM-Breadth (Level-wise Approach)
- **Đại diện chạy:** `ExperimentLPPMBreadth` $\rightarrow$ gọi class `AlgoLPPMBreadth2`
- **Cơ chế code:**
  - Áp dụng nguyên lý duyệt BFS tương tự thuật toán sinh ứng viên *Apriori*, thay vì dùng Pointer nó ánh xạ DB gốc sang dạng Dọc (Vertical Database) đại diện bằng các lớp **`BitSet`** trong Java.
  - Từ LPP độ dài 1, BFS join để sinh tập ứng viên cấp $k+1$. Logic so khớp pattern là việc thi hành các phép **Logical AND** cấp tốc trên mảng BitSet. Chỗ nào mảng có giá trị 1 đáp ứng gap <= maxPer thì được ghi nhận.
- **Thực tế benchmark:** BitSet cực kỳ ngốn RAM. Với 990K tx của kosarak, nó đè lên heap ảo `OutOfMemoryError` vì Memory cần lưu cho mỗi itemset cần khoảng >2GB~5GB RAM.

### 3. Thuật toán LPPM-Depth (Equivalence Class Approach)
- **Đại diện chạy:** `ExperimentLPPMDepth` $\rightarrow$ gọi class `AlgoLPPMDepth2`
- **Cơ chế code:**
  - Giữ lại lõi Vertical DB dạng `BitSet` như Breadth, nhưng kỹ thuật duyệt lật sang Đệ Quy Chiều Sâu (Depth-First Search) vay mượn từ Eclat.
  - Phân nhánh hàm tìm kiếm dựa trên các **Equivalence Class** - tất cả LPP con chia sẻ chung tiền tố (Prefix) được thu hồi và rẽ qua chung một nhánh logic hàm đệ quy `processEquivalenceClass()`, điều này hạn chế việc duyệt lại dư thừa.
- **Thực tế benchmark:** Tối giản Node lưu trữ hơn BFS nên với DB nhỏ chạy ổn định, nhưng vẫn gặp tử huyệt sập RAM trên kosarak do "gánh nặng bẩm sinh" của Vertical Database.

---

## 🚀 Hướng Dẫn Chạy Môi Trường Của Thầy (Quick Start)

Kết quả benchmark sẽ được tổng hợp ở thư mục `/outputs`.

**Để chạy kiểm tra đơn lẻ (Ví dụ: thử thuật toán LPPM-Depth):**
```bash
java -Xmx2g -cp bin ca.pfv.spmf.experiment.ExperimentLPPMDepth
```

**Để chạy Benchmark toàn bộ để xem báo cáo tự động:**
*(Master Engine sẽ biên dịch file CSV so sánh tự động - yêu cầu để `-Xmx4g` heapsize để quan sát rõ hiệu suất BitSet đụng trần RAM)*
```bash
java -Xmx4g -cp bin ca.pfv.spmf.experiment.RunAllExperiments
```
