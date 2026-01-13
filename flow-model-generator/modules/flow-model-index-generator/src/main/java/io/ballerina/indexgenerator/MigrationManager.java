/*
 *  Copyright (c) 2024, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.indexgenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Manages database schema migrations for the central index.
 *
 * @since 2.0.0
 */
public class MigrationManager {

    private static final Logger LOGGER = Logger.getLogger(MigrationManager.class.getName());
    private final String dbPath;

    public MigrationManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * Checks if the schema_migrations table exists.
     */
    private boolean migrationTableExists(Connection conn) throws SQLException {
        String sql = "SELECT name FROM sqlite_master WHERE type='table' AND name='schema_migrations'";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next();
        }
    }

    /**
     * Gets the current schema version.
     */
    public int getCurrentVersion() {
        try (Connection conn = DriverManager.getConnection(dbPath)) {
            if (!migrationTableExists(conn)) {
                return 0; // No migrations applied yet
            }

            String sql = "SELECT MAX(version) as max_version FROM schema_migrations";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt("max_version");
                }
                return 0;
            }
        } catch (SQLException e) {
            LOGGER.severe("Error getting current version: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Checks if a specific migration has been applied.
     */
    public boolean isMigrationApplied(int version) {
        try (Connection conn = DriverManager.getConnection(dbPath)) {
            if (!migrationTableExists(conn)) {
                return false;
            }

            String sql = "SELECT COUNT(*) as count FROM schema_migrations WHERE version = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, version);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt("count") > 0;
                }
                return false;
            }
        } catch (SQLException e) {
            LOGGER.severe("Error checking migration status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Applies a migration script from file.
     */
    public boolean applyMigration(Path migrationFilePath) {
        try {
            String sql = Files.readString(migrationFilePath);
            return executeMigrationSql(sql);
        } catch (IOException e) {
            LOGGER.severe("Error reading migration file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Executes migration SQL.
     */
    private boolean executeMigrationSql(String sql) {
        try (Connection conn = DriverManager.getConnection(dbPath)) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Split by semicolon and execute each statement
                String[] statements = sql.split(";");
                for (String statement : statements) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                        stmt.executeUpdate(trimmed);
                    }
                }

                conn.commit();
                LOGGER.info("Migration applied successfully");
                return true;
            } catch (SQLException e) {
                conn.rollback();
                LOGGER.severe("Error applying migration (rolled back): " + e.getMessage());
                return false;
            }
        } catch (SQLException e) {
            LOGGER.severe("Error connecting to database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Applies migration version 001 (add type definitions).
     */
    public boolean applyMigration001() {
        if (isMigrationApplied(1)) {
            LOGGER.info("Migration 001 already applied");
            return true;
        }

        Path migrationPath = Path.of(
                "flow-model-generator/modules/flow-model-index-generator/src/main/resources/migrations",
                "001_add_type_definitions.sql"
        );

        if (!Files.exists(migrationPath)) {
            LOGGER.severe("Migration file not found: " + migrationPath);
            return false;
        }

        LOGGER.info("Applying migration 001: Add type definitions");
        return applyMigration(migrationPath);
    }

    /**
     * Rolls back migration version 001.
     */
    public boolean rollbackMigration001() {
        if (!isMigrationApplied(1)) {
            LOGGER.info("Migration 001 not applied, nothing to rollback");
            return true;
        }

        Path rollbackPath = Path.of(
                "flow-model-generator/modules/flow-model-index-generator/src/main/resources/migrations",
                "001_add_type_definitions_rollback.sql"
        );

        if (!Files.exists(rollbackPath)) {
            LOGGER.severe("Rollback file not found: " + rollbackPath);
            return false;
        }

        LOGGER.info("Rolling back migration 001: Add type definitions");
        return applyMigration(rollbackPath);
    }

    /**
     * Creates a backup of the current database.
     */
    public boolean createBackup(Path backupPath) {
        try {
            // Extract database file path from JDBC URL
            String dbFile = dbPath.replace("jdbc:sqlite:", "");
            Path sourcePath = Path.of(dbFile);

            if (!Files.exists(sourcePath)) {
                LOGGER.severe("Database file not found: " + sourcePath);
                return false;
            }

            Files.copy(sourcePath, backupPath);
            LOGGER.info("Database backed up to: " + backupPath);
            return true;
        } catch (IOException e) {
            LOGGER.severe("Error creating backup: " + e.getMessage());
            return false;
        }
    }

    /**
     * Main method for running migrations.
     */
    public static void main(String[] args) {
        String dbPath = "jdbc:sqlite:" + Path.of(
                "flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources",
                "central-index.sqlite"
        ).toString();

        MigrationManager manager = new MigrationManager(dbPath);

        // Create backup before migration
        Path backupPath = Path.of("central-index-backup-" + System.currentTimeMillis() + ".sqlite");
        if (!manager.createBackup(backupPath)) {
            LOGGER.severe("Failed to create backup. Aborting migration.");
            return;
        }

        // Check current version
        int currentVersion = manager.getCurrentVersion();
        LOGGER.info("Current schema version: " + currentVersion);

        // Apply migrations
        if (args.length > 0 && args[0].equals("rollback")) {
            // Rollback mode
            LOGGER.info("Rolling back migrations...");
            manager.rollbackMigration001();
        } else {
            // Forward migration mode
            LOGGER.info("Applying migrations...");
            if (manager.applyMigration001()) {
                LOGGER.info("All migrations applied successfully");
            } else {
                LOGGER.severe("Migration failed. Restore from backup: " + backupPath);
            }
        }

        // Report final version
        int finalVersion = manager.getCurrentVersion();
        LOGGER.info("Final schema version: " + finalVersion);
    }
}
