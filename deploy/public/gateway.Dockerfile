# The allowlisting gateway: the only process that accepts requests from outside the VM.
FROM node:22-alpine@sha256:1314fb30ec37592d5845e5a160d522c0e680972aa534549940b8d73bde88481a
WORKDIR /app
COPY funnel-gateway.mjs .
USER node
CMD ["node", "funnel-gateway.mjs"]
