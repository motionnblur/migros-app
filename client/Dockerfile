FROM node:22-alpine

WORKDIR /App

RUN npm i -g @angular/cli
COPY package*.json ./
RUN npm i

COPY . .

CMD ["ng", "serve", "--host", "0.0.0.0"]
