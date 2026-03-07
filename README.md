# 💰 Expense Tracker — Android App

A lightweight personal expense tracker for Android. Built with Java + SQLite.

---

## 📁 File Structure

```
ExpenseTracker/
├── README.md                          ← You are here
├── app/
│   ├── build.gradle                   ← Dependencies (replace in Android Studio)
│   └── src/main/
│       ├── AndroidManifest.xml        ← Replace existing
│       ├── java/com/example/expensetracker/
│       │   ├── Transaction.java       ← NEW file
│       │   ├── DatabaseHelper.java    ← NEW file
│       │   ├── MainActivity.java      ← Replace existing
│       │   ├── AddTransactionActivity.java    ← NEW file
│       │   ├── TransactionHistoryActivity.java ← NEW file
│       │   └── TransactionAdapter.java        ← NEW file
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml               ← Replace existing
│           │   ├── activity_add_transaction.xml    ← NEW file
│           │   ├── activity_transaction_history.xml ← NEW file
│           │   └── item_transaction.xml            ← NEW file
│           └── values/
│               ├── colors.xml    ← Replace existing
│               ├── strings.xml   ← Replace existing
│               └── themes.xml    ← Replace existing
```

---

## 🛠️ Setup Steps in Android Studio

### Step 1 — Create a New Project
1. Open Android Studio
2. Click **New Project**
3. Choose **Empty Views Activity**
4. Set these values:
   - Name: `Expense Tracker`
   - Package: `com.example.expensetracker`
   - Language: `Java`
   - Min SDK: `API 21 (Android 5.0)`
5. Click **Finish** and wait for Gradle to sync

### Step 2 — Replace / Add Files
Copy each file from this ZIP into the exact matching location in your project.

- Files marked **"Replace"** → delete the existing file, paste the new one
- Files marked **"NEW"** → just paste them into the correct folder

### Step 3 — Sync Gradle
- Click **File → Sync Project with Gradle Files**
- Wait for it to complete (requires internet to download libraries)

### Step 4 — Build & Install on Phone
1. Enable **Developer Options** on your Android phone:
   - Go to Settings → About Phone → tap **Build Number** 7 times
2. Enable **USB Debugging** in Developer Options
3. Connect phone via USB cable
4. In Android Studio, select your phone from the device dropdown
5. Click the green ▶ **Run** button
6. The app installs and opens on your phone automatically!

### Alternative — Build APK manually
- Click **Build → Build Bundle(s)/APK(s) → Build APK(s)**
- Find the APK at: `app/build/outputs/apk/debug/app-debug.apk`
- Transfer it to your phone and install

---

## 📱 App Features

| Feature | Description |
|---|---|
| Balance | Shows current balance on home screen, turns red if negative |
| + Income | Add a credit transaction with amount + description |
| − Expense | Add a debit transaction with amount + description |
| History | Full transaction list, newest first, colored rows |
| Pagination | 50 transactions loaded at a time, Load More button |
| Currency | ₹ (Indian Rupee) — stored as plain numbers in SQLite |

---

## 🎨 Color Scheme

- **Green rows** = Credit / Income
- **Red rows** = Debit / Expense
- Balance turns **red** if it goes negative

