# FS-G definition only -- NOT built or deployed by anything in this repository.
# Build context: the repository root. Only the server code and its one runtime dependency go in;
# no keys, no google-services.json, no .env, no app sources.
#
# package.json is the rules test harness (firebase-tools and friends are devDependencies), so the
# image installs firebase-admin alone, at the version package-lock.json pins. A test
# (tests/deploy/fs-g-config.test.mjs) keeps the two versions equal.
FROM node:22-slim
WORKDIR /srv
ENV NODE_ENV=production
RUN npm init -y >/dev/null && npm pkg set type=module \
 && npm install --save-exact --ignore-scripts --omit=dev firebase-admin@13.10.0
COPY server/ ./server/
USER node
CMD ["node", "server/http.mjs"]
