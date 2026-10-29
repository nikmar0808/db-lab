// Runs once, the first time the MongoDB container starts with an empty volume (lesson A2), logged in as
// the administrator. Creates the application's account, the MongoDB counterpart of PostgreSQL's lab_app.
// Users are kept in the database "admin", as Atlas does; the role limits this one to the database utility_lab.
db.getSiblingDB('admin').createUser({
  user: 'lab_app',
  pwd: process.env.MONGO_APP_PASSWORD,               // from .env through docker-compose.yml, never written here
  roles: [{ role: 'readWrite', db: 'utility_lab' }]  // read and change data in utility_lab, and nothing else
});