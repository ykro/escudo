FROM node:20-slim

WORKDIR /app
ENV PORT=8080

COPY package.json server.mjs gate.test.mjs ./
COPY public ./public

# Run the automated security & crypto gate test suite during build
RUN node --test /app/gate.test.mjs

EXPOSE 8080
CMD ["node", "/app/server.mjs"]
