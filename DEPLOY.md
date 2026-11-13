# Despliegue de GeoFastPe en la nube (gratis)

## Arquitectura

| Parte | Servicio | Plan | Por qué |
|---|---|---|---|
| Base de datos | **Neon** (PostgreSQL) | Free | Datos persistentes, sin tarjeta |
| Backend (Spring Boot) | **Render** (Docker) | Free | Lee el `Dockerfile` y `render.yaml` del proyecto |
| Frontend (React + Vite) | **Vercel** | Hobby | Build automático, HTTPS (necesario para el GPS), rutas SPA |
| Asistente IA | **Groq** | Free | Ya configurado; la clave va como variable secreta |
| Monitor anti-sueño | **UptimeRobot** | Free | Evita que Render duerma el backend por inactividad |

Las condiciones de los planes gratuitos cambian. Antes de crear cada cuenta revisa la página de precios.

## Orden de subida

1. **GitHub** (repositorio): Render y Vercel despliegan desde GitHub. Es el paso 0.
2. **Neon** (base de datos): el backend la necesita para arrancar.
3. **Render** (backend): necesita la base de datos. Aquí se genera el enlace público del backend.
4. **Vercel** (frontend): necesita el enlace del backend para compilar.
5. **Volver a Render**: poner el enlace de Vercel en `CORS_ALLOWED_ORIGINS`.
6. **UptimeRobot**: monitor del backend.
7. **Groq**: revocar la clave que se escribió en el chat y crear una nueva.

---

## Paso 0: GitHub

1. Crea cuenta en https://github.com (gratis).
2. Crea un repositorio **privado** llamado `geofastpe` (sin README).
3. Instala Git si no lo tienes: https://git-scm.com
4. En la carpeta raíz del proyecto (`backend_frontend`) ejecuta:

```powershell
git init
git add .
git status
git commit -m "GeoFastPe listo para despliegue"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/geofastpe.git
git push -u origin main
```

Antes del `git push`, revisa en `git status` que **no** aparezcan `.env`, `backend/data/`, `backend/uploads/` ni `node_modules/`. Esos archivos ya están en los `.gitignore`.

---

## Paso 1: Base de datos en Neon

1. Entra a https://neon.tech y crea cuenta (con GitHub).
2. Crea un proyecto: nombre `geofastpe`, región cercana (por ejemplo `AWS South America (São Paulo)`).
3. En el panel del proyecto, pulsa **Connect** y copia la cadena que tiene este formato:

```
postgresql://USUARIO:CLAVE@ep-xxxx.sa-east-1.aws.neon.tech/neondb?sslmode=require
```

4. Conviértela a los tres valores que usa Spring Boot:

| Variable | Valor |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://ep-xxxx.sa-east-1.aws.neon.tech/neondb?sslmode=require` (empieza con `jdbc:`, sin `USUARIO:CLAVE@`) |
| `SPRING_DATASOURCE_USERNAME` | el `USUARIO` |
| `SPRING_DATASOURCE_PASSWORD` | la `CLAVE` |

Guárdalos en un bloc de notas privado. No los pegues en el código ni en el chat.

---

## Paso 2: Backend en Render

1. Entra a https://render.com y crea cuenta con GitHub.
2. Pulsa **New > Blueprint**, elige el repositorio `geofastpe`. Render detecta `render.yaml` y crea el servicio `geofastpe-backend`.
3. Antes de confirmar, completa las variables marcadas como secretas:

| Variable | Qué poner |
|---|---|
| `SPRING_DATASOURCE_URL` | el valor del paso 1 |
| `SPRING_DATASOURCE_USERNAME` | el usuario de Neon |
| `SPRING_DATASOURCE_PASSWORD` | la clave de Neon |
| `CORS_ALLOWED_ORIGINS` | por ahora `http://localhost:5173`; lo cambias en el paso 5 |
| `ASSISTANT_LLM_API_KEY` | tu clave nueva de Groq (ver paso 7) |

`JWT_SECRET` se genera solo. Para verla o cambiarla, ve a **Environment** del servicio.

4. Pulsa **Apply** y espera el primer build (5 a 10 minutos).
5. Cuando termine, copia la URL pública, con la forma `https://geofastpe-backend.onrender.com`.
6. Prueba: `https://geofastpe-backend.onrender.com/actuator/health` debe responder `{"status":"UP"}`.

Notas del plan gratuito:
- El servicio **se duerme tras 15 minutos sin uso**. La primera visita tarda 30 a 60 segundos. El paso 6 lo evita.
- Los archivos subidos (fotos) **se pierden al reiniciar**. Para guardarlos de forma permanente, el siguiente paso recomendado es Cloudinary (plan gratuito).

---

## Paso 3: Frontend en Vercel

1. Entra a https://vercel.com y crea cuenta con GitHub.
2. Pulsa **Add New > Project** y elige `geofastpe`.
3. Configura:
   - **Root Directory**: `frontend`
   - **Framework Preset**: Vite (lo detecta solo)
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
4. En **Environment Variables** agrega:

| Nombre | Valor |
|---|---|
| `VITE_API_URL` | `https://geofastpe-backend.onrender.com/api/v1` |

5. Pulsa **Deploy**. Vercel te da una URL como `https://geofastpe.vercel.app`.

`VITE_API_URL` se graba al compilar. Si cambias el backend, debes volver a desplegar el frontend (**Deployments > Redeploy**).

Alternativa gratuita: Netlify (https://netlify.com). El proyecto ya tiene `vercel.json`; para Netlify basta la misma variable `VITE_API_URL` y la regla de redirección a `index.html`.

---

## Paso 4: Probar el frontend

Abre la URL de Vercel y prueba:
- Carga del mapa.
- Registro e inicio de sesión (la base de datos está vacía, así que regístrate de nuevo).
- Asistente IA: "busca un restaurante cerca".
- Publicar una solicitud de ayuda desde el asistente.

---

## Paso 5: Conectar CORS

Ahora que tienes la URL de Vercel, vuelve a Render:

1. Servicio `geofastpe-backend` > **Environment**.
2. Cambia `CORS_ALLOWED_ORIGINS` a la URL exacta de Vercel, **sin barra final**:

```
https://geofastpe.vercel.app
```

Si tienes un dominio propio, sepáralos con coma: `https://geofastpe.vercel.app,https://tudominio.com`.

3. Guarda. Render reinicia el servicio solo.

Sin este paso el navegador bloquea las llamadas al backend.

---

## Paso 6: Monitor anti-sueño (UptimeRobot)

1. Crea cuenta gratis en https://uptimerobot.com.
2. **New monitor**:
   - Type: `HTTP(s)`
   - URL: `https://geofastpe-backend.onrender.com/actuator/health`
   - Monitoring interval: `5 minutes`
3. Así el backend casi nunca se duerme.

---

## Paso 7: Seguridad de la clave de Groq

La clave que escribiste en este chat debe considerarse expuesta:

1. Entra a https://console.groq.com/keys.
2. Revoca la clave actual.
3. Crea una nueva.
4. Ponla **solo** en Render (`ASSISTANT_LLM_API_KEY`) y, si la usas en local, como variable de entorno de tu usuario de Windows. Nunca en archivos del proyecto.

---

## Variables de entorno del backend (resumen)

| Variable | Obligatoria | Ejemplo / nota |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | sí | `prod` (ya viene en `render.yaml`) |
| `SPRING_DATASOURCE_URL` | sí | `jdbc:postgresql://...?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | sí | usuario de Neon |
| `SPRING_DATASOURCE_PASSWORD` | sí | clave de Neon |
| `JWT_SECRET` | sí | generada por Render. Si la cambias, usa al menos 48 caracteres aleatorios |
| `CORS_ALLOWED_ORIGINS` | sí | URL de Vercel, sin barra final |
| `ASSISTANT_LLM_API_KEY` | para el asistente IA | clave de Groq |
| `ASSISTANT_LLM_MODEL` | no | por defecto `openai/gpt-oss-120b` |
| `UPLOAD_DIR` | no | por defecto `/tmp/geofastpe-uploads` (temporal) |

Para generar un `JWT_SECRET` propio:

```powershell
node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"
```

---

## Limitaciones honestas del plan gratuito

- **Fotos:** se pierden al reiniciar Render. Solución: Cloudinary (gratis).
- **Primera carga lenta:** el backend duerme; UptimeRobot reduce el problema, pero no lo elimina por completo.
- **Base de datos vacía:** los datos locales no se suben automáticamente.
- **Límites de Groq:** el plan gratuito tiene límite diario. Cuando se agote, el asistente usa el motor local.
- **Vercel Hobby** es para uso personal y no comercial. Si lo usarás comercialmente, revisa las condiciones o usa Netlify.

## Lista de comprobación final

- [ ] `https://geofastpe-backend.onrender.com/actuator/health` responde `UP`.
- [ ] La app de Vercel carga el mapa y los servicios.
- [ ] El login funciona (sin errores de CORS en la consola del navegador).
- [ ] El asistente responde con la clave de Groq nueva.
- [ ] La clave antigua de Groq fue revocada.
- [ ] `git status` no mostró `.env` ni `data/` antes de subir.
