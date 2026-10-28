# Deploying the frontend on Vercel

Vercel hosts only the React frontend. The backend (Kafka, ingestion, analytics, PostgreSQL, Redis) must run on a Docker host with a public HTTPS URL, see [deployment.md](deployment.md). Vercel cannot proxy WebSockets, so the browser talks to the backend directly.

## 1. Backend first

Run the Compose stack on a VPS or EC2 with HTTPS, for example `https://api.example.com` (`DOMAIN` in `.env` plus `docker compose --profile https up -d --build`). In the backend `.env` set the Vercel origin so the browser is allowed to call it:

```
CORS_ALLOWED_ORIGINS=https://your-app.vercel.app
```

Add every origin you use (comma separated), including a custom domain. Restart the analytics container after changing it.

## 2. Create the Vercel project

1. Vercel dashboard, **Add New, Project**, import `VarshaSathiskumar/Github-Real-time-monitor`.
2. Set **Root Directory** to `frontend`. Everything else is read from `frontend/vercel.json` (Vite, `npm run build`, output `dist`, SPA fallback).

## 3. Environment variables (Project Settings, Environment Variables)

Set them for Production (and Preview if you use it). They are read **at build time**, so redeploy after changing them.

| Variable | Required | Example | Meaning |
|---|---|---|---|
| `VITE_API_BASE_URL` | yes | `https://api.example.com` | Base URL of the analytics service. No trailing slash. The app calls `/api/...` on it. |
| `VITE_REFRESH_MODE` | yes (for Vercel) | `polling` | The deployed site refreshes from the REST endpoints every 3 seconds instead of opening a WebSocket, and says so in the page footer. The WebSocket endpoint stays in the backend and is used by the Docker Compose setup. |
| `VITE_WS_URL` | only without polling | `wss://api.example.com/ws` | Full WebSocket URL. Not used when `VITE_REFRESH_MODE=polling`. |
| `VITE_GITHUB_REPO_URL` | no | `https://github.com/VarshaSathiskumar/Github-Real-time-monitor` | Target of the "View Source on GitHub" button. Defaults to this repository. |

Leaving `VITE_API_BASE_URL` empty makes the app call its own origin (Vercel), which has no backend, so the dashboard would show "Cannot reach the analytics service".

## 4. Deploy and verify

Deploy, open the Vercel URL and check:

- the header shows **LIVE** and **Connected**
- the browser network tab shows `GET https://api.example.com/api/metrics` returning 200 (a CORS error means `CORS_ALLOWED_ORIGINS` on the backend does not match the page origin exactly, including `https://` and no trailing slash)
- `GET .../api/metrics` repeats about every 3 seconds (no WebSocket is opened)

## Notes

- A custom domain on Vercel needs to be added to `CORS_ALLOWED_ORIGINS` as well.
- Preview deployments get their own URLs, so they need to be listed too or they will fail CORS.
- Never put secrets in `VITE_*` variables: they are embedded in the public JavaScript. None of the variables above are secret.
