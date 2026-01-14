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
import java.util.List;
import java.util.logging.Logger;

class DatabaseManager {

    private static final Logger LOGGER = Logger.getLogger(DatabaseManager.class.getName());
    private static final String INDEX_FILE_NAME = "central-index-enhanced.sqlite";
    private static final String CENTRAL_INDEX_SQL = "central-index.sql";
    private static final String dbPath = getDatabasePath();

    private static String getDatabasePath() {
        String destinationPath =
                Path.of("flow-model-generator/modules/flow-model-generator-ls-extension/src/main/resources")
                        .resolve(INDEX_FILE_NAME)
                        .toString();
        return "jdbc:sqlite:" + destinationPath;
    }

    private static int insertEntry(String sql, Object[] params) {
        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                stmt.setObject(i + 1, params[i]);
            }
            stmt.executeUpdate();
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1);
                } else {
                    throw new SQLException("Creating package failed, no ID obtained.");
                }
            }
        } catch (SQLException e) {
            LOGGER.severe("Error executing query: " + e.getMessage());
            return -1;
        }
    }

    public static void createDatabase() {
        Path destinationPath =
                Path.of("flow-model-generator/modules/flow-model-index-generator/src/main/resources")
                        .resolve(CENTRAL_INDEX_SQL);
        try {
            String sql = Files.readString(destinationPath);
            executeQuery(sql);
        } catch (IOException e) {
            LOGGER.severe("Error reading SQL file: " + e.getMessage());
        }
    }

    public static void executeQuery(String sql) {
        try (Connection conn = DriverManager.getConnection(dbPath);
             Statement stmt = conn.createStatement()) { // Use Statement instead
            stmt.executeUpdate(sql);
            LOGGER.info("Database created successfully");
        } catch (SQLException e) {
            LOGGER.severe("Error executing query: " + e.getMessage());
        }
    }

    public static int insertPackage(String org, String packageName, String moduleName, String version,
                                    List<String> keywords) {
        String sql = "INSERT INTO Package (org, package_name, module_name, version, keywords) VALUES (?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{org, packageName, moduleName, version,
                keywords == null ? "" : String.join(",", keywords)});
    }

    public static int insertFunction(int packageId, String name, String description, String returnType, String kind,
                                     String resourcePath, int returnError, boolean inferredReturnType,
                                     String importStatements) {
        String sql = "INSERT INTO Function (package_id, name, description, " +
                "return_type, kind, resource_path, return_error, inferred_return_type, import_statements) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, name, description,
                returnType, kind, resourcePath, returnError, inferredReturnType ? 1 : 0, importStatements});
    }

    public static int insertFunctionParameter(int functionId, String paramName, String paramDescription,
                                              Object paramType, String placeholder, String defaultValue,
                                              IndexGenerator.FunctionParameterKind parameterKind,
                                              int optional, String importStatements, String label) {

        String sql =
                "INSERT INTO Parameter (function_id, name, description, type, placeholder, default_value, kind, " +
                        "optional, " +
                        "import_statements, label) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        return insertEntry(sql,
                new Object[]{functionId, paramName, paramDescription, paramType, placeholder, defaultValue,
                        parameterKind.name(), optional, importStatements, label});
    }

    public static void insertParameterMemberType(int parameterId, String type, String kind,
                                                 String packageIdentifier, String packageName) {
        String sql = "INSERT INTO ParameterMemberType (parameter_id, type, kind, package_identifier, package_name) " +
                "VALUES (?, ?, ?, ?, ?)";
        insertEntry(sql, new Object[]{parameterId, type, kind, packageIdentifier, packageName});
    }

    public static void mapConnectorAction(int actionId, int connectorId) {
        String sql = "INSERT INTO FunctionConnector (function_id, connector_id) VALUES (?, ?)";
        insertEntry(sql, new Object[]{actionId, connectorId});
    }

    public static void updateTypeParameter(String moduleName, String oldType, String newType) {
        String sql1 = "UPDATE Parameter " +
                "SET type = REPLACE(type, ?, ?) " +
                "WHERE parameter_id IN (" +
                "    SELECT pa.parameter_id" +
                "    FROM Package p" +
                "    JOIN Function f ON p.package_id = f.package_id" +
                "    JOIN Parameter pa ON f.function_id = pa.function_id" +
                "    WHERE p.module_name = ?" +
                "    AND pa.type LIKE ?" +
                ")";

        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql1)) {
            stmt.setString(1, oldType);
            stmt.setString(2, newType);
            stmt.setString(3, moduleName);
            stmt.setString(4, "%" + oldType + "%");

            int rowsUpdated = stmt.executeUpdate();
            LOGGER.info(rowsUpdated + " parameter records updated for " + moduleName);
        } catch (SQLException e) {
            LOGGER.severe("Error updating parameter types: " + e.getMessage());
        }

        String sql2 = "UPDATE Function " +
                "SET return_type = REPLACE(return_type, ?, ?) " +
                "WHERE package_id IN (" +
                "    SELECT package_id" +
                "    FROM Package" +
                "    WHERE module_name = ?" +
                "    AND return_type LIKE ?" +
                ")";

        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql2)) {
            stmt.setString(1, oldType);
            stmt.setString(2, newType);
            stmt.setString(3, moduleName);
            stmt.setString(4, "%" + oldType + "%");

            int rowsUpdated = stmt.executeUpdate();
            LOGGER.info(rowsUpdated + " return type records updated for " + moduleName);
        } catch (SQLException e) {
            LOGGER.severe("Error updating return types: " + e.getMessage());
        }
    }

    public static int insertTypeDefinition(int packageId, String name, String description, String typeCategory,
                                           String baseType) {
        String sql = "INSERT INTO TypeDefinition (package_id, name, description, type_category, base_type) " +
                    "VALUES (?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, name, description, typeCategory, baseType});
    }

    public static int insertRecordField(int typeId, String name, String description, String fieldType, int optional) {
        String sql = "INSERT INTO RecordField (type_id, name, description, field_type, optional) " +
                    "VALUES (?, ?, ?, ?, ?)";
        return insertEntry(sql, new Object[]{typeId, name, description, fieldType, optional});
    }

    public static void insertEnumMember(int typeId, String name, String description, int ordinal) {
        String sql = "INSERT INTO EnumMember (type_id, name, description, ordinal) VALUES (?, ?, ?, ?)";
        insertEntry(sql, new Object[]{typeId, name, description, ordinal});
    }

    public static void insertUnionMember(int typeId, String memberTypeName, int ordinal) {
        String sql = "INSERT INTO UnionMember (type_id, member_type_name, ordinal) VALUES (?, ?, ?)";
        insertEntry(sql, new Object[]{typeId, memberTypeName, ordinal});
    }

    public static void insertClassMethod(int classTypeId, int functionId, String methodType) {
        String sql = "INSERT INTO ClassMethod (class_type_id, function_id, method_type) VALUES (?, ?, ?)";
        insertEntry(sql, new Object[]{classTypeId, functionId, methodType});
    }

    public static int insertClientDefinition(int packageId, String name, String description) {
        String sql = "INSERT INTO ClientDefinition (package_id, name, description) VALUES (?, ?, ?)";
        return insertEntry(sql, new Object[]{packageId, name, description});
    }

    public static void insertServiceDefinition(int packageId, String serviceType, String instructions,
                                              String listenerName, String listenerConfig,
                                              String testGenerationInstruction) {
        String sql = "INSERT INTO ServiceDefinition (package_id, service_type, instructions, listener_name, " +
                    "listener_config, test_generation_instruction) VALUES (?, ?, ?, ?, ?, ?)";
        insertEntry(sql, new Object[]{packageId, serviceType, instructions, listenerName, listenerConfig,
                                      testGenerationInstruction});
    }

    public static void insertTypeLink(int sourceFieldId, String targetTypeName, String category,
                                     String packageOrg, String packageName) {
        String sql = "INSERT INTO TypeLink (source_field_id, target_type_name, category, package_org, package_name) " +
                    "VALUES (?, ?, ?, ?, ?)";
        insertEntry(sql, new Object[]{sourceFieldId, targetTypeName, category, packageOrg, packageName});
    }

    // ===== Helper Query Methods =====

    /**
     *
     * Get package_id by package name (format: "org/package").
     */
    public static int getPackageIdByName(String fullPackageName) {
        // Parse "ballerina/http" format
        String[] parts = fullPackageName.split("/");
        if (parts.length != 2) {
            LOGGER.warning("Invalid package name format: " + fullPackageName);
            return -1;
        }

        String org = parts[0];
        String packageName = parts[1];

        String sql = "SELECT package_id FROM Package WHERE org = ? AND package_name = ?";

        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, org);
            stmt.setString(2, packageName);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("package_id");
            }
        } catch (SQLException e) {
            LOGGER.severe("Error querying package: " + e.getMessage());
        }

        return -1;
    }

    /**
     * Get type_id by name within a package.
     */
    public static int getTypeIdByName(int packageId, String typeName) {
        String sql = "SELECT type_id FROM TypeDefinition WHERE package_id = ? AND name = ?";

        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, packageId);
            stmt.setString(2, typeName);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("type_id");
            }
        } catch (SQLException e) {
            LOGGER.severe("Error querying type: " + e.getMessage());
        }

        return -1;
    }

    /**
     * Get field_id for a record field.
     */
    public static int getRecordFieldId(int typeId, String fieldName) {
        String sql = "SELECT field_id FROM RecordField WHERE type_id = ? AND name = ?";

        try (Connection conn = DriverManager.getConnection(dbPath);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, typeId);
            stmt.setString(2, fieldName);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("field_id");
            }
        } catch (SQLException e) {
            LOGGER.severe("Error querying record field: " + e.getMessage());
        }

        return -1;
    }
}
