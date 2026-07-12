# FamilyAlbum

Private family photo and video album built with Spring Boot, Vue 3, PostgreSQL, and S3-compatible object storage.

## Local development

1. Copy `.env.example` to `.env` and fill in your bucket name and access keys.
2. Start PostgreSQL:

```powershell
docker compose -p family-album up -d postgres
```

3. Run the backend:

```powershell
cd backend
$env:JAVA_HOME='C:\Users\Wansu\.jdks\openjdk-26.0.1-1'
.\mvnw.cmd spring-boot:run
```

4. Run the frontend:

```powershell
cd frontend
npm install
npm run dev
```

The frontend runs on `http://localhost:5173` and proxies `/api` to the backend on `http://localhost:8080`.

## Storage layout

```text
originals/yyyy/mm/dd/<uuid>.<ext>
variants/yyyy/mm/dd/<uuid>-1080p.mp4
variants/yyyy/mm/dd/<uuid>-720p.mp4
thumbs/yyyy/mm/dd/<uuid>.jpg
```

For browser direct uploads, configure the Lightsail bucket CORS policy to allow `PUT` from the frontend origin.

## Production deploy

Production deployment is designed for a Lightsail instance running Docker Compose.

See [docs/lightsail-deploy.md](docs/lightsail-deploy.md).
