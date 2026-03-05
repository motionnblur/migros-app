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
    * [TypeScript](https://www.typescriptlang.org/)
    * [HTML](https://developer.mozilla.org/en-US/docs/Web/HTML)
    * [CSS](https://developer.mozilla.org/en-US/docs/Web/CSS)
* **Backend:**
    * [Spring Boot](https://spring.io/projects/spring-boot) (3.4.0)
    * [Java](https://www.java.com/) (21)
    * [Maven](https://maven.apache.org/)
* **Database:**
    * [PostgreSQL](https://www.postgresql.org/) (latest)

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
<img width="772" height="261" alt="Screenshot 2026-03-05 122252" src="https://github.com/user-attachments/assets/7da7d1d4-6ba4-4d8c-8f69-da4fa0c4ea85" />

<img width="1920" height="981" alt="migros-main-page" src="https://github.com/user-attachments/assets/6a01280c-282a-429c-b83e-897dc00cbdbc" />

<img width="1914" height="934" alt="migros" src="https://github.com/user-attachments/assets/b2f7fde7-d8bd-4dc0-bb17-befd252f3a40" />

<img width="1914" height="942" alt="migros" src="https://github.com/user-attachments/assets/7b96dfb6-c56b-42a1-92f1-68f38b5df847" />

<img width="965" height="470" alt="migros" src="https://github.com/user-attachments/assets/4154b6d8-f1c1-4b62-9ee8-1d6b570f6084" />

<img width="1916" height="979" alt="migros" src="https://github.com/user-attachments/assets/bf88288c-b0da-42ad-b7e5-366857ccb25f" />

<img width="1916" height="976" alt="migros" src="https://github.com/user-attachments/assets/27eabaf4-b5ee-4494-8b1f-833caac9697b" />

<img width="1920" height="975" alt="migros" src="https://github.com/user-attachments/assets/0dbb124b-4eb6-4011-8c82-6b8de99b0928" />

<img width="1918" height="975" alt="migros" src="https://github.com/user-attachments/assets/c890668d-31f9-425e-8e7a-eea45d8ced80" />

<img width="1912" height="974" alt="migros" src="https://github.com/user-attachments/assets/3ba7b422-f5c7-45f4-8fe4-7ab368d91db4" />

<img width="1912" height="969" alt="migros" src="https://github.com/user-attachments/assets/51e1330c-daa8-4898-b0a8-49b63659e1b0" />

<img width="1920" height="971" alt="migros" src="https://github.com/user-attachments/assets/b6aaa8d5-840d-4703-a126-506a2e66015b" />

<img width="1918" height="975" alt="migros" src="https://github.com/user-attachments/assets/5c33f8f2-cfbf-43a1-ba8d-c5dad313e65a" />

<img width="1912" height="977" alt="migros" src="https://github.com/user-attachments/assets/42025bb2-6203-4b0a-b789-b5cbd218f1bb" />

<img width="1920" height="977" alt="migros" src="https://github.com/user-attachments/assets/85c20c50-194d-47b6-a56b-a9330b80e559" />
