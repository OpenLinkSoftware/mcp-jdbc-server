package openlink.mcp.server.jdbc;

import java.util.List;
import java.util.Map;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;


public class MCPServer {

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "jdbc.user")
    Optional<String> jdbcUser;

    @ConfigProperty(name = "jdbc.password")
    Optional<String> jdbcPassword;

    @ConfigProperty(name = "jdbc.max_long_data")
    Optional<Integer> MAX_LONG_DATA;

    @ConfigProperty(name = "jdbc.api_key")
    Optional<String> API_KEY;

    private Connection getConnection(String user, String password, String url) throws SQLException 
    {
        if (user==null)
          user = jdbcUser.orElse(null);
        if (password==null)
          password = jdbcPassword.orElse(null);
        if (url==null)
          url = jdbcUrl;

        return DriverManager.getConnection(url, user, password);
    }


    @Tool(description = "Retrieve and return a list of all schema names from the connected database.")
    String jdbc_get_schemas(McpLog log,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        try (Connection conn = getConnection(user, password, url)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getCatalogs();

            List<String> cats = new ArrayList<>();
            while (rs.next()) {
                cats.add(rs.getString("TABLE_CAT"));
            }
            return mapper.writeValueAsString(cats);
        } catch (Exception e) {
            throw new ToolCallException("Failed to get_schemas: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Retrieve and return a list containing information about tables in specified schema, if empty uses connection default")
    String jdbc_get_tables(McpLog log,
    	@ToolArg(description = "Schema name", required = false) Optional<String> schema,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String cat = schema.orElse("%");
        try (Connection conn = getConnection(user, password, url)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTables(cat, null, "%", new String[] { "TABLE" });

            List<Map<String, String>> tables = new ArrayList<>();
            while (rs.next()) {
                Map<String, String> table = new HashMap<>();
                table.put("TABLE_CAT", rs.getString("TABLE_CAT"));
                table.put("TABLE_SCHEM", rs.getString("TABLE_SCHEM"));
                table.put("TABLE_NAME", rs.getString("TABLE_NAME"));
                tables.add(table);
            }
            return mapper.writeValueAsString(tables);
        } catch (Exception e) {
            throw new ToolCallException("Failed to get_tables: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Retrieve and return a list containing information about tables in specified schema, if empty uses connection default")
    String jdbc_describe_table(McpLog log,
    	@ToolArg(description = "Schema name", required = false) Optional<String> schema,
    	@ToolArg(description = "Table name", required = true) String table,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String cat = schema.orElse("%");
        Map<String, Object> tableDefinition = new HashMap<>();

        try (Connection conn = getConnection(user, password, url)) {
            Map<String, Object> tableInfo = hasTable(conn, cat, table);
            if ((Boolean) tableInfo.get("exists")) {
                tableDefinition = getTableInfo(conn,
                                               (String) tableInfo.get("cat"),
                                               (String) tableInfo.get("sch"),
                                               (String) tableInfo.get("name"));
            }
            return mapper.writeValueAsString(tableDefinition);
        } catch (Exception e) {
            throw new ToolCallException("Failed to describe_table: " + e.getMessage(), e);
        }
    }

    
    private Map<String, Object> hasTable(Connection conn, String cat, String table) throws SQLException 
    {
        Map<String, Object> result = new HashMap<>();
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getTables(cat, null, table, null);
        
        if (rs.next()) {
            result.put("exists", true);
            result.put("cat", rs.getString(1));
            result.put("sch", rs.getString(2));
            result.put("name", rs.getString(3));
        } else {
            result.put("exists", false);
        }
        
        return result;
    }
    
    private List<Map<String, Object>> getColumns(Connection conn, String cat, String sch, String table) throws SQLException 
    {
        List<Map<String, Object>> columns = new ArrayList<>();
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getColumns(cat, sch, table, null);
        
        while (rs.next()) {
            Map<String, Object> column = new HashMap<>();
            column.put("name", rs.getString("COLUMN_NAME"));
            column.put("type", rs.getString("TYPE_NAME"));
            column.put("column_size", rs.getInt("COLUMN_SIZE"));
            column.put("num_prec_radix", rs.getInt("NUM_PREC_RADIX"));
            column.put("nullable", rs.getInt("NULLABLE") != 0);
            column.put("default", rs.getString("COLUMN_DEF"));
            columns.add(column);
        }
        
        return columns;
    }
    
    private Map<String, Object> getPkConstraint(Connection conn, String cat, String sch, String table) throws SQLException 
    {
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getPrimaryKeys(cat, sch, table);
        
        List<String> columns = new ArrayList<>();
        String name = null;
        
        while (rs.next()) {
            columns.add(rs.getString("COLUMN_NAME"));
            if (name == null) {
                name = rs.getString("PK_NAME");
            }
        }
        
        if (!columns.isEmpty()) {
            Map<String, Object> constraint = new HashMap<>();
            constraint.put("constrained_columns", columns);
            constraint.put("name", name);
            return constraint;
        }
        
        return null;
    }
    
    private List<Map<String, Object>> getForeignKeys(Connection conn, String cat, String sch, String table) throws SQLException 
    {
        DatabaseMetaData metaData = conn.getMetaData();
        ResultSet rs = metaData.getImportedKeys(cat, sch, table);
        
        Map<String, Map<String, Object>> fkeysMap = new HashMap<>();
        
        while (rs.next()) {
            String fkName = rs.getString("FK_NAME");

            Map<String, Object> fkey = fkeysMap.get(fkName);
            if (fkey == null) {
                fkey = new HashMap<>();
                fkey.put("name", fkName);
                fkey.put("constrained_columns", new ArrayList<String>());
                fkey.put("referred_cat", rs.getString("PKTABLE_CAT"));
                fkey.put("referred_schem", rs.getString("PKTABLE_SCHEM"));
                fkey.put("referred_table", rs.getString("PKTABLE_NAME"));
                fkey.put("referred_columns", new ArrayList<String>());
                fkey.put("options", new HashMap<>());
                fkeysMap.put(fkName, fkey);
            }
                    
            @SuppressWarnings("unchecked")
            List<String> constrainedColumns = (List<String>) fkey.get("constrained_columns");
            constrainedColumns.add(rs.getString("FKCOLUMN_NAME"));
            
            @SuppressWarnings("unchecked")
            List<String> referredColumns = (List<String>) fkey.get("referred_columns");
            referredColumns.add(rs.getString("PKCOLUMN_NAME"));
        }
        
        return new ArrayList<>(fkeysMap.values());
    }
    
    private Map<String, Object> getTableInfo(Connection conn, String cat, String sch, String table) throws SQLException 
    {
        List<Map<String, Object>> columns = getColumns(conn, cat, sch, table);
        Map<String, Object> pkConstraint = getPkConstraint(conn, cat, sch, table);
        List<String> primaryKeys = pkConstraint != null ? 
                              (List<String>) pkConstraint.get("constrained_columns") : 
                              new ArrayList<>();
        List<Map<String, Object>> foreignKeys = getForeignKeys(conn, cat, sch, table);
        
        Map<String, Object> tableInfo = new HashMap<>();
        tableInfo.put("TABLE_CAT", cat);
        tableInfo.put("TABLE_SCHEM", sch);
        tableInfo.put("TABLE_NAME", table);
        tableInfo.put("columns", columns);
        tableInfo.put("primary_keys", primaryKeys);
        tableInfo.put("foreign_keys", foreignKeys);
        
        // Mark columns that are primary keys
        for (Map<String, Object> column : columns) {
            column.put("primary_key", primaryKeys.contains(column.get("name")));
        }
        
        return tableInfo;
    }
    

    @Tool(description = "Retrieve and return a list containing information about tables whose names contain the substring 'q' .")
    String jdbc_filter_table_names(McpLog log,
    	@ToolArg(description = "substring got search", required = true) String q,
    	@ToolArg(description = "Schema name", required = false) Optional<String> schema,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String cat = schema.orElse("%");
        Map<String, Object> tableDefinition = new HashMap<>();

        try (Connection conn = getConnection(user, password, url)) {
            DatabaseMetaData metaData = conn.getMetaData();
            ResultSet rs = metaData.getTables(cat, null, "%", new String[] { "TABLE" });

            List<Map<String, String>> tables = new ArrayList<>();
            while (rs.next()) {
                String tbl = rs.getString("TABLE_NAME");
                if (tbl.contains(q)) {
                    Map<String, String> table = new HashMap<>();
                    table.put("TABLE_CAT", rs.getString("TABLE_CAT"));
                    table.put("TABLE_SCHEM", rs.getString("TABLE_SCHEM"));
                    table.put("TABLE_NAME", rs.getString("TABLE_NAME"));
                    tables.add(table);
                }
            }
            return mapper.writeValueAsString(tables);
        } catch (Exception e) {
            throw new ToolCallException("Failed to filter table names: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Execute a SQL query and return results in JSONL format.")
    String jdbc_execute_query(McpLog log,
    	@ToolArg(description = "Query", required = true) String query,
    	@ToolArg(description = "Max Rows", required = false) Optional<Integer> max_rows,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        int maxRowsValue = max_rows.orElse(100);

        try (Connection conn = getConnection(user, password, url)) {
            Statement stmt = conn.createStatement();
            
            ResultSet rs = stmt.executeQuery(query);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            List<String> jsonlRows = new ArrayList<>();
            int rowCount = 0;
            int max_long_data = MAX_LONG_DATA.orElse(100);
            
            while (rs.next() && rowCount < maxRowsValue) {
                Map<String, Object> row = new HashMap<>();
                
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    
                    if (value != null) {
                        String stringValue = value.toString();
                        if (stringValue.length() > max_long_data) {
                            stringValue = stringValue.substring(0, max_long_data);
                        }
                        row.put(columnName, stringValue);
                    } else {
                        row.put(columnName, null);
                    }
                }
                
                jsonlRows.add(mapper.writeValueAsString(row));
                rowCount++;
            }
            return String.join("\n", jsonlRows);
        } catch (Exception e) {
            throw new ToolCallException("Failed to execute_query: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Execute a SQL query and return results in Markdown table format.")
    String jdbc_execute_query_md(McpLog log,
    	@ToolArg(description = "Query", required = true) String query,
    	@ToolArg(description = "Max Rows", required = false) Optional<Integer> max_rows,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        int maxRowsValue = max_rows.orElse(100);

        try (Connection conn = getConnection(user, password, url)) {
            Statement stmt = conn.createStatement();
            
            ResultSet rs = stmt.executeQuery(query);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(metaData.getColumnName(i));
            }
            
            StringBuilder mdTable = new StringBuilder();

            // Create the Markdown table header
            mdTable.append("| ").append(String.join(" | ", columnNames)).append(" |\n");
            mdTable.append("| ").append(columnNames.stream().map(c -> "---").collect(Collectors.joining(" | "))).append(" |\n");
            
            // Add rows to the Markdown table
            int rowCount = 0;
            int max_long_data = MAX_LONG_DATA.orElse(100);

            while (rs.next() && rowCount < maxRowsValue) {
                mdTable.append("| ");
                
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    String displayValue = value == null ? "" : value.toString();
                    
                    if (displayValue.length() > max_long_data) {
                        displayValue = displayValue.substring(0, max_long_data);
                    }
                    
                    mdTable.append(displayValue).append(" | ");
                }
                
                mdTable.append("\n");
                rowCount++;
            }
            
            return mdTable.toString();
        } catch (Exception e) {
            throw new ToolCallException("Failed to execute_query_md: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Execute a SQL query and return results in JSONL format.")
    String jdbc_query_database(McpLog log,
    	@ToolArg(description = "Query", required = true) String query,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        try (Connection conn = getConnection(user, password, url)) {
            Statement stmt = conn.createStatement();
            
            ResultSet rs = stmt.executeQuery(query);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            
            List<String> jsonlRows = new ArrayList<>();
            int max_long_data = MAX_LONG_DATA.orElse(100);
            
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    
                    if (value != null) {
                        String stringValue = value.toString();
                        if (stringValue.length() > max_long_data) {
                            stringValue = stringValue.substring(0, max_long_data);
                        }
                        row.put(columnName, stringValue);
                    } else {
                        row.put(columnName, null);
                    }
                }
                
                jsonlRows.add(mapper.writeValueAsString(row));
            }
            return String.join("\n", jsonlRows);
        } catch (Exception e) {
            throw new ToolCallException("Failed to query_database: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Execute a SPASQL query and return results.")
    String jdbc_spasql_query(McpLog log,
    	@ToolArg(description = "Query", required = true) String query,
    	@ToolArg(description = "Max Rows", required = false) Optional<Integer> max_rows,
    	@ToolArg(description = "Timeout", required = false) Optional<Integer> timeout,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        int maxRowsValue = max_rows.orElse(20);
        int timeoutValue = timeout.orElse(300000);

        try (Connection conn = getConnection(user, password, url)) {
            String cmd = "select Demo.demo.execute_spasql_query(?,?,?) as result";

            PreparedStatement stmt = conn.prepareStatement(cmd);
            stmt.setString(1, query);
            stmt.setInt(2, maxRowsValue);
            stmt.setInt(3, timeoutValue);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getString(1);
        } catch (Exception e) {
            throw new ToolCallException("Failed to spasql_query: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Execute a SPARQL query and return results.")
    String jdbc_sparql_query(McpLog log,
    	@ToolArg(description = "Query", required = true) String query,
    	@ToolArg(description = "Max Rows", required = false) Optional<String> format,
    	@ToolArg(description = "Timeout", required = false) Optional<Integer> timeout,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String formatValue = format.orElse("json");
        int timeoutValue = timeout.orElse(300000);

        try (Connection conn = getConnection(user, password, url)) {
            //"select \"UB\".dba.\"sparqlQuery\"('{escape_sql(query)}', ?, ?) as result"
            String cmd = "select \"UB\".dba.\"sparqlQuery\"(?, ?, ?) as result";

            PreparedStatement stmt = conn.prepareStatement(cmd);
            stmt.setString(1, query);
            stmt.setString(2, formatValue);
            stmt.setInt(3, timeoutValue);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getString(1);
        } catch (Exception e) {
            throw new ToolCallException("Failed to sparql_query: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Interact with Virtuoso Support AI Agent")
    String jdbc_virtuoso_support_ai(McpLog log,
    	@ToolArg(description = "Prompt", required = true) String prompt,
    	@ToolArg(description = "API Key", required = false) Optional<String> api_key,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String _api_key = api_key.orElse( API_KEY.orElse("sk-xxx"));

        try (Connection conn = getConnection(user, password, url)) {
            String cmd = "select DEMO.DBA.OAI_VIRTUOSO_SUPPORT_AI(?, ?) as result";

            PreparedStatement stmt = conn.prepareStatement(cmd);
            stmt.setString(1, prompt);
            stmt.setString(2, _api_key);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getString(1);
        } catch (Exception e) {
            throw new ToolCallException("Failed to virtuoso_support_ai: " + e.getMessage(), e);
        }
    }


    @Tool(description = "Use the SPARQL AI Agent function")
    String jdbc_sparql_func(McpLog log,
    	@ToolArg(description = "Prompt", required = true) String prompt,
    	@ToolArg(description = "API Key", required = false) Optional<String> api_key,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");
        String _api_key = api_key.orElse( API_KEY.orElse("sk-xxx"));

        try (Connection conn = getConnection(user, password, url)) {
            String cmd = "select DEMO.DBA.OAI_SPARQL_FUNC(?, ?) as result";

            PreparedStatement stmt = conn.prepareStatement(cmd);
            stmt.setString(1, prompt);
            stmt.setString(2, _api_key);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getString(1);
        } catch (Exception e) {
            throw new ToolCallException("Failed to sparql_func: " + e.getMessage(), e);
        }
    }


    @Tool(description="This query retrieves all entity types in the RDF graph, along with their labels and comments if available. "
                +"It filters out blank nodes and ensures that only IRI types are returned. "
                +"The LIMIT clause is set to 100 to restrict the number of entity types returned. ")
    String jdbc_sparql_get_entity_types(McpLog log,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");

        String query = """
    SELECT DISTINCT * FROM (
      SPARQL 
      PREFIX owl: <http://www.w3.org/2002/07/owl#>
      PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
      PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> 
      SELECT ?o 
      WHERE {
        GRAPH ?g {
            ?s a ?o .
            
            OPTIONAL {
                ?s rdfs:label ?label . 
                FILTER (LANG(?label) = "en" || LANG(?label) = "")
            }
            
            OPTIONAL {
                ?s rdfs:comment ?comment . 
                FILTER (LANG(?comment) = "en" || LANG(?comment) = "")
            }
            
            FILTER (isIRI(?o) && !isBlank(?o))
        }
      }
      LIMIT 100
    ) AS x 
    """;
        return jdbc_query_database(log, query, user, password, url);
    }


    @Tool(description="This query retrieves all entity types in the RDF graph, along with their labels and comments if available. "
                +"It filters out blank nodes and ensures that only IRI types are returned. "
                +"The LIMIT clause is set to 100 to restrict the number of entity types returned.")
    String jdbc_sparql_get_entity_types_detailed(McpLog log,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");

        String query = """
    SELECT * FROM (
        SPARQL
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> 

        SELECT ?o, (SAMPLE(?label) AS ?label), (SAMPLE(?comment) AS ?comment)
        WHERE {
            GRAPH ?g {
                ?s a ?o .
                OPTIONAL {?o rdfs:label ?label . FILTER (LANG(?label) = "en" || LANG(?label) = "")}
                OPTIONAL {?o rdfs:comment ?comment . FILTER (LANG(?comment) = "en" || LANG(?comment) = "")}
                FILTER (isIRI(?o) && !isBlank(?o))
            }
        }
        GROUP BY ?o
        ORDER BY ?o
        LIMIT 20
    ) AS results 
    """;
        return jdbc_query_database(log, query, user, password, url);
    }


    @Tool(description="This query retrieves samples of entities for each type in the RDF graph, along with their labels and counts. "
                +"It groups by entity type and orders the results by sample count in descending order. "
                +"Note: The LIMIT clause is set to 20 to restrict the number of entity types returned.")
    String jdbc_sparql_get_entity_types_samples(McpLog log,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");

        String query = """
    SELECT * FROM (
        SPARQL
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> 
        SELECT (SAMPLE(?s) AS ?sample), ?slabel, (COUNT(*) AS ?sampleCount), (?o AS ?entityType), ?olabel
        WHERE {
            GRAPH ?g {
                ?s a ?o .
                OPTIONAL {?s rdfs:label ?slabel . FILTER (LANG(?slabel) = \"en\" || LANG(?slabel) = \"\")}
                FILTER (isIRI(?s) && !isBlank(?s))
                OPTIONAL {?o rdfs:label ?olabel . FILTER (LANG(?olabel) = \"en\" || LANG(?olabel) = \"\")}
                FILTER (isIRI(?o) && !isBlank(?o))
            }
        }
        GROUP BY ?slabel ?o ?olabel
        ORDER BY DESC(?sampleCount) ?o ?slabel ?olabel
        LIMIT 20
    ) AS results
    """;
        return jdbc_query_database(log, query, user, password, url);
    }


    @Tool(description="This query retrieves all ontologies in the RDF graph, along with their labels and comments if available.")
    String jdbc_sparql_get_ontologies(McpLog log,
    	@ToolArg(description = "Username", required = false) String user,
    	@ToolArg(description = "Password", required = false) String password,
    	@ToolArg(description = "JDBC URL", required = false) String url) 
    {
        //log.debug("Listing tables");
        //log.error("Listing tables");

        String query = """
    SELECT * FROM (
        SPARQL 
        PREFIX owl: <http://www.w3.org/2002/07/owl#>
        PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
        PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
        SELECT ?s, ?label, ?comment 
        WHERE {
            GRAPH ?g {
                ?s a owl:Ontology .
            
                OPTIONAL {
                    ?s rdfs:label ?label . 
                    FILTER (LANG(?label) = "en" || LANG(?label) = "")
                }
            
                OPTIONAL {
                    ?s rdfs:comment ?comment . 
                    FILTER (LANG(?comment) = "en" || LANG(?comment) = "")
                }
            
                FILTER (isIRI(?o) && !isBlank(?o))
            }
        }
        LIMIT 100
    ) AS x
    """;
        return jdbc_query_database(log, query, user, password, url);
    }


/**
    @Prompt(description = "Visualize ER diagram")
    PromptMessage er_diagram() {
        return PromptMessage.withUserRole(new TextContent(
                """
                        The assistants goal is to use the MCP server to create a visual ER diagram of the database.
                        """));
    }

**/



}
