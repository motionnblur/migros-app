# Migros App

This is a basic e-commerce mvp application i built using Angular for the frontend, Spring Boot for the backend, and PostgreSQL for the database.

It's a very basic migros clone that i built for my learning purposes. See: www.migros.com.tr

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Technologies Used](#technologies-used)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Running the Application](#running-the-application)
- [Screenshots](#screenshots)

## Overview

This application provides a basic platform for users to browse products, add them to a shopping cart, and potentially simulate a checkout process (depending on the level of implementation). It serves as a foundation for a more comprehensive e-commerce solution.

## Features

* **Admin Dashboard:** For managing orders and products.
* **Product Listing:** Display a list of available products.
* **Product Details:** View detailed information about a specific product.
* **Add to Cart:** Allow users to add products to their shopping cart.
* **View Cart:** Display the items in the shopping cart.
* **Payment Processing:** By using Stripe's payment test api.
* **Basic Security:** User Authentication and Authorization.

## Technologies Used

* **Frontend:**
    * [Angular](https://angular.io/)  (19.0.5)
    * [Material UI](https://material.angularjs.org/latest/) (19.0.5)
    * [Bootstrap](https://getbootstrap.com/)
    * [TypeScript](https://www.typescriptlang.org/)
    * [HTML](https://developer.mozilla.org/en-US/docs/Web/HTML)
    * [CSS](https://developer.mozilla.org/en-US/docs/Web/CSS)
* **Backend:**
    * [Spring Boot](https://spring.io/projects/spring-boot) (3.4.0)
    * [Java](https://www.java.com/) (21)
    * [Maven](https://maven.apache.org/)
* **Database:**
    * [PostgreSQL](https://www.postgresql.org/) (postgres:18.3-alpine3.22)
* **Docker:**
    * [Docker](https://www.docker.com/)

## Prerequisites

Before you begin, ensure you have the following installed:

* **Docker**

## Setup

Before getting started, you need __configs__ folder (migros-app/configs). It's a secret folder that should't be available for the public, so you'll need to create your own.
1. Create an empty folder named __configs__
2. Go into that folder
3. Create two empty file named __postgres.env__ and __spring.env__

For postgres.env file, contents should be in that way:
* POSTGRES_USER=postgres (your postgres name)
* POSTGRES_PASSWORD=1 (your postgress password)
* POSTGRES_DB=migros_db (the database name of the project)

For spring.env file, contents should be in that way:
* SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/(the database name of the project, migros_db, as an example.)
* SPRING_DATASOURCE_USERNAME=postgres (your postgres name)
* SPRING_DATASOURCE_PASSWORD=1 (your postgress password)

For testing purposes:
* spring.env:
```
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/migros_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=1
```
* postgres.env:
```
POSTGRES_USER=postgres
POSTGRES_PASSWORD=1
POSTGRES_DB=migros_db
```
* Default admin name: admin
* Default admin password: admin

## Running the application
Firstly you need to initialize node packages in order the client to run, go into client folder and type:
```
npm i
```
To start the development server, just type the command below in your favorite terminal:
```
docker compose --env-file configs/postgres.env --env-file configs/spring.env up
```

## Screenshots

* Current code coverage
<img width="791" height="367" alt="Screenshot 2026-03-05 124449" src="https://github.com/user-attachments/assets/34abb1eb-5424-4434-89bb-2131b45742ae" />

<img width="1890" height="944" alt="Screenshot 2026-03-05 195555" src="https://github.com/user-attachments/assets/ee61d7ca-6e80-4e10-99b4-2a80ccf6921e" />

<img width="1886" height="940" alt="Screenshot 2026-03-05 195657" src="https://github.com/user-attachments/assets/41366479-6ba0-4666-bb06-6ba8e8965cd9" />

<img width="1883" height="941" alt="Screenshot 2026-03-05 201407" src="https://github.com/user-attachments/assets/fba0f37b-1c3d-4799-b878-1e871708d472" />

<img width="1889" height="942" alt="Screenshot 2026-03-05 201004" src="https://github.com/user-attachments/assets/42386dd7-8569-4dca-8825-8325d45992a3" />

<img width="1888" height="942" alt="Screenshot 2026-03-05 201023" src="https://github.com/user-attachments/assets/7bc4cfc5-0a45-4af1-b68e-bcb4a6309bc2" />

<img width="1903" height="940" alt="Screenshot 2026-03-05 195825" src="https://github.com/user-attachments/assets/2400b7ae-d72e-476a-8bb8-2126535d567d" />

<img width="1902" height="938" alt="Screenshot 2026-03-05 195842" src="https://github.com/user-attachments/assets/b2dd9ac1-be5a-438c-8b10-8c215f6e0e53" />

<img width="1897" height="936" alt="Screenshot 2026-03-05 200848" src="https://github.com/user-attachments/assets/435ed628-2f1e-4bd3-baa2-3b44afda3887" />

<img width="1898" height="938" alt="Screenshot 2026-03-05 200930" src="https://github.com/user-attachments/assets/559cca7f-2907-404f-b0c2-2f8d72ec7a04" />

<img width="1897" height="939" alt="Screenshot 2026-03-05 195927" src="https://github.com/user-attachments/assets/b0471ed9-a66a-4fea-ae46-91119b3051e2" />

<img width="1897" height="934" alt="Screenshot 2026-03-05 200001" src="https://github.com/user-attachments/assets/4ee131c8-69f1-4a1d-ac32-d0a47c6d3cbd" />

<img width="1885" height="941" alt="Screenshot 2026-03-05 202031" src="https://github.com/user-attachments/assets/184d33a4-3432-4638-8ffc-4e4b6fc72b48" />

<img width="1884" height="943" alt="Screenshot 2026-03-05 202157" src="https://github.com/user-attachments/assets/afdcfb3a-cd98-4733-99d6-8cf72e84f49b" />

<img width="1896" height="939" alt="Screenshot 2026-03-05 202641" src="https://github.com/user-attachments/assets/af39c798-1bfe-4ead-8e36-f3bbef2cb069" />

<img width="1896" height="937" alt="Screenshot 2026-03-05 202658" src="https://github.com/user-attachments/assets/bc67a44b-5504-492a-acd4-921ef9c8310d" />

<img width="1881" height="943" alt="Screenshot 2026-03-05 203236" src="https://github.com/user-attachments/assets/6d87cb4f-d431-4d72-a0b0-3b939300f442" />

<img width="1884" height="943" alt="Screenshot 2026-03-05 202718" src="https://github.com/user-attachments/assets/a752d917-0444-4a87-80d9-f43b16ca6616" />
