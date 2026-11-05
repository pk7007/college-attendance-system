# College Attendance Demo

This is a small, browser-only demo. It runs without Java, Docker, MySQL, npm, or an internet connection.

## Open the app

Open `demo/index.html` in a browser. On Windows, double-click the file in File Explorer.

If your browser blocks local files, run this from the repository folder:

```powershell
python -m http.server 8000 --bind 127.0.0.1 --directory demo
```

Then visit <http://localhost:8000>. Stop the server with `Ctrl+C`.

## Demo accounts

All accounts use the password `demo123`.

| Role | Email |
| --- | --- |
| Admin | `admin@college.test` |
| Faculty | `faculty@college.test` |
| Student | `student@college.test` |

The demo includes sample class sessions, an admin overview and basic people/classes screens, faculty attendance marking, student attendance summaries and history, plus a CSV report.

## Move it with GitHub

1. Push this repository to GitHub.
2. On the other laptop, clone or download the repository.
3. Open `demo/index.html` in a browser.

The app stores changes in that browser's local storage. They do not sync through GitHub or move to another device. Use **Reset sample data** in the app to restore the sample records. This demo is not secure authentication or storage; do not enter real student records or passwords.
