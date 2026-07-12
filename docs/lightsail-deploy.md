# Lightsail Deploy

## 1. Prepare the server

Install Docker and create the app directory on the Lightsail instance.

```bash
sudo mkdir -p /opt/familyalbum
sudo chown "$USER":"$USER" /opt/familyalbum
```

Create `/opt/familyalbum/.env` on the server:

```env
POSTGRES_DB=family_album
POSTGRES_USER=familyalbum
POSTGRES_PASSWORD=replace-with-strong-password

AWS_ACCESS_KEY_ID=replace-me
AWS_SECRET_ACCESS_KEY=replace-me
AWS_REGION=ap-northeast-2
S3_BUCKET=replace-with-your-bucket-name
S3_ENDPOINT=https://s3.ap-northeast-2.amazonaws.com

APP_CORS_ALLOWED_ORIGINS=https://album.joopapa.com
```

## 2. GitHub repository secrets

Add these secrets in GitHub:

```text
LIGHTSAIL_HOST
LIGHTSAIL_USER
LIGHTSAIL_SSH_KEY
```

`LIGHTSAIL_SSH_KEY` should be the private SSH key that can log in to the Lightsail instance.

## 3. First deploy

Push to `main` or manually run the `Deploy` workflow from GitHub Actions.

The workflow builds backend and frontend images, pushes them to GitHub Container Registry, copies `docker-compose.prod.yml` to the server, and restarts the app.
## 4. Reverse proxy

This Lightsail server already has host Nginx listening on `80` and `443`.

FamilyAlbum exposes the frontend only on localhost:

```text
127.0.0.1:8081 -> frontend container 80
```

Create an Nginx site for your FamilyAlbum domain:

```bash
sudo nano /etc/nginx/sites-available/familyalbum
```

Example config:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name album.joopapa.com;

    client_max_body_size 50m;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Enable it:

```bash
sudo ln -s /etc/nginx/sites-available/familyalbum /etc/nginx/sites-enabled/familyalbum
sudo nginx -t
sudo systemctl reload nginx
```

If Certbot is installed, issue HTTPS after DNS points to the Lightsail public IP:

```bash
sudo certbot --nginx -d album.joopapa.com
```