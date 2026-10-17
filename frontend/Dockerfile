# The GUI is its own service, so it needs its own server. Two stages: Node
# builds the bundle, nginx serves it, and nothing from the build survives into
# the image that runs.
FROM node:22-alpine AS build
WORKDIR /frontend
COPY package*.json ./
RUN npm ci
COPY . .
# Vite inlines every VITE_* value here, at build time. They come from the
# committed .env.production; .dockerignore keeps a developer's .env.local out of
# the context so this build cannot quietly inherit local configuration.
# The Auth0 client id is the exception: it is a variable on the Railway frontend
# service, which Railway passes in as this build argument, and an environment
# variable beats .env.production in Vite.
ARG VITE_AUTH0_CLIENT_ID
RUN npm run build

FROM nginx:alpine
# The official image runs envsubst over everything in this directory at
# container start. That is the only way an nginx configuration can learn the
# port the platform assigned it.
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /frontend/dist /usr/share/nginx/html
# A default so the image also runs outside a platform that assigns one. Railway
# overrides it.
ENV PORT=8080
# envsubst would otherwise substitute every environment variable it finds, and a
# platform injects a great many. nginx's own runtime variables are written the
# same way — $uri below is nginx syntax, not a placeholder — so an injected
# variable that happened to share a name would rewrite the configuration into
# something that silently does the wrong thing. Only PORT is a placeholder here.
ENV NGINX_ENVSUBST_FILTER=^PORT$
