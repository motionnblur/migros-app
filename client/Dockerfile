FROM node:25-alpine3.22

WORKDIR /App

COPY package*.json ./

RUN npm i -g @angular/cli
RUN npm install

COPY . .

CMD ["ng", "serve", "--host", "0.0.0.0", "--proxy-config", "proxy.conf.cjs"]
