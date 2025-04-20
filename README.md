# Migros App

This is a basic e-commerce mvp application i built using Angular for the frontend, Spring Boot for the backend, and PostgreSQL for the database.

It's a very basic migros clone that i built for my learning purposes. See: www.migros.com.tr

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Technologies Used](#technologies-used)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
  - [Backend Setup (Spring Boot)](#backend-setup-spring-boot)
  - [Frontend Setup (Angular)](#frontend-setup-angular)
  - [Database Setup (PostgreSQL)](#database-setup-postgresql)
- [Running the Application](#running-the-application)
  - [Running the Backend](#running-the-backend)
  - [Running the Frontend](#running-the-frontend)
- [API Endpoints](#api-endpoints) (Example - Adapt to your actual endpoints)
- [Database Schema](#database-schema) (Optional - Provide a simplified overview)
- [Contributing](#contributing)
- [License](#license)

## Overview

This application provides a basic platform for users to browse products, add them to a shopping cart, and potentially simulate a checkout process (depending on the level of implementation). It serves as a foundation for a more comprehensive e-commerce solution.

## Features

* **Admin Dashboard:** For managing orders and products.
* **Product Listing:** Display a list of available products.
* **Product Details:** View detailed information about a specific product.
* **Add to Cart:** Allow users to add products to their shopping cart.
* **View Cart:** Display the items in the shopping cart.
* **Basic Security** User Authentication and Authorization.

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

To start the development server, just type the command below in your favorite terminal.
```
docker compose --env-file configs/postgres.env --env-file configs/spring.env up
```
