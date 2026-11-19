# Sprintly MVP demo

## Before recording

Start the local backend with a migrated, seeded PostgreSQL database. Check that `GET http://127.0.0.1:3000/health` returns `200`. Open the debug app on an emulator with networking enabled and wait for **Đăng nhập**. [Setup and test commands](../README.md)

Use `owner@sprintly.demo` and the password set in `DEMO_OWNER_PASSWORD` when the account was first seeded. The seed also provisions `member@sprintly.demo` with `DEMO_MEMBER_PASSWORD`. Both accounts can open **Sprintly Demo** and its **Sprintly Launch** project. A fresh project starts with **To Do**, **In Progress** and **Done**. Re-running the seed preserves existing passwords and project content.

## Three-minute walkthrough

| Time | Action | What to show |
| --- | --- | --- |
| 0:00–0:25 | Sign in as the demo owner. Open **Sprintly Demo**, then **Sprintly Launch** under **Dự án**. | The project opens its **Bảng Kanban** board. |
| 0:25–0:55 | Select **To Do** and tap **Tạo công việc**. Enter `Release QA` in **Tiêu đề**, choose **Cao** under **Ưu tiên**, and select **Demo Member** under **Người phụ trách**. Tap **Tạo công việc** to save. | The card appears in **To Do** with its priority and assignee. |
| 0:55–1:25 | Press and drag the card's handle into **In Progress**. Hold near the right edge to bring the next column into view on a narrow screen. Tap **Làm mới**, then select **In Progress**. | The card remains in its new column after the board reloads. |
| 1:25–1:55 | Open `Release QA`, tap **Thêm bình luận**, enter `Ready for the release check.` and tap **Gửi**. | The comment appears under **Bình luận**. Show the creation and move entries under **Lịch sử**. |
| 1:55–2:30 | Return to the board. Enable airplane mode and turn off Wi‑Fi if it stays on. Open the same card, then return to the board and restore the network. | **Đang xem dữ liệu đã lưu** appears. The saved task remains readable, and **Tạo công việc** is disabled until the board refreshes. |
| 2:30–2:55 | Return to the workspace list. Open **Hồ sơ**, tap **Đăng xuất**, then confirm. | Sprintly returns to **Đăng nhập**. |

For a search demonstration, use **Tìm và lọc công việc** on the connected board. Search for `Release QA`, then apply the **In Progress** column and **Cao** priority filters under **Bộ lọc công việc**. **Xóa bộ lọc** clears the selected filters and keeps the query.
