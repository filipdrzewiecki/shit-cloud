# shit-cloud

Monorepo: Spring Boot API at the root, React (Vite) GUI in `frontend/`. On Railway they are two
separate services from the same repo — see `shitcloud-handoff.md` for the full plan.

## First time

```
npm install
npm install --prefix frontend
cp frontend/.env.example frontend/.env.local
```

## Run

```
npm run dev
```

Open http://localhost:3000 — backend on 8080, frontend on 3000 (cross-origin, no proxy).

## Run separately

```
mvn spring-boot:run                 # backend
npm run dev --prefix frontend       # frontend
```

## Railway

Project `shitcloud`, environment `test`, two services from this repo. Build/deploy settings live in
the Railway service config (config-as-code `railway.json` is deprecated there).

| Service  | Root directory | Domain                                    | Variables                                    |
|----------|----------------|-------------------------------------------|----------------------------------------------|
| api      | `/`            | https://api-test-27f4.up.railway.app      | `PORT=8080`, `CORS_ALLOWED_ORIGINS`          |
| frontend | `/frontend`    | https://frontend-test-7caa.up.railway.app | `PORT=8080` (API URL from `.env.production`) |
