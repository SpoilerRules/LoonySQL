package uk.co.loonyrules.sql;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import uk.co.loonyrules.sql.annotations.Column;
import uk.co.loonyrules.sql.annotations.Primary;
import uk.co.loonyrules.sql.annotations.Table;
import uk.co.loonyrules.sql.codecs.Codec;
import uk.co.loonyrules.sql.enums.ModifyType;
import uk.co.loonyrules.sql.models.Tables;
import uk.co.loonyrules.sql.utils.MapUtil;
import uk.co.loonyrules.sql.utils.ReflectionUtil;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The main class that allows you to manage the entire Database
 * as the authenticated {@link Credentials} user.
 */
public class Database
{

    private final Credentials credentials;

    private HikariDataSource hikariDataSource;
    private ExecutorService executorService;
    private Thread shutdownThread;

    /**
     * Initialise a new Database connection using a set of {@link Credentials}
     * @param credentials to connect and authenticate with
     */
    public Database(Credentials credentials)
    {
        // Ensuring the Credentials object isn't instance
        Preconditions.checkNotNull(credentials, "Credentials cannot be null.");

        // Assigning the Credentials variable
        this.credentials = credentials;
    }

    /**
     * Get the {@link HikariDataSource} for this Database
     * @return {@link HikariDataSource} this Database uses
     */
    public HikariDataSource getHikariDataSource()
    {
        return hikariDataSource;
    }

    /**
     * Get the {@link ExecutorService} used for asynchronous query calls
     * @return {@link ExecutorService} for this Database
     */
    public ExecutorService getExecutorService()
    {
        return executorService;
    }

    /**
     * Get a new {@link Connection} from the {@link HikariDataSource}
     * @return A new {@link Connection} from the {@link HikariDataSource}
     * @throws SQLException if an error is encountered
     */
    public Connection getConnection() throws SQLException
    {
        // Ensuring we're connected before retrieving a Connection
        Preconditions.checkArgument(isConnected(), "Connection hasn't been initialised.");

        // Return a new Connection
        return hikariDataSource.getConnection();
    }

    /**
     * Check if the {@link HikariDataSource} is closed
     * @return if the {@link HikariDataSource} is closed
     */
    public boolean isConnected()
    {
        return hikariDataSource != null && !hikariDataSource.isClosed();
    }

    /**
     * Attempt to connect using the {@link Credentials} given.
     */
    public void connect()
    {
        // Ensuring we're not already connected
        Preconditions.checkArgument(!isConnected(), "Already connected to the Database.");

        // Initialise our HikariConfig
        HikariConfig hikariConfig = new HikariConfig();

        // Setting the dataSource's class name
        hikariConfig.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");

        // Setting the Jdbc url
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql//%s:%s/%s", credentials.getHost(), credentials.getPort(), credentials.getDatabase()));

        // Adding our databaseName as a property
        hikariConfig.addDataSourceProperty("databaseName", credentials.getDatabase());

        // Setting the authentication credentials
        hikariConfig.setUsername(credentials.getUsername());
        hikariConfig.setPassword(credentials.getPassword());

        // Setting our timeout
        hikariConfig.setConnectionTimeout(credentials.getTimeout());

        // Initialising the Data Source
        hikariDataSource = new HikariDataSource(hikariConfig);

        // Creating our executor for this Database
        executorService = Executors.newCachedThreadPool();

        // Add a shutdown hook
        Runtime.getRuntime().addShutdownHook(shutdownThread = new Thread(this::disconnect));
    }

    /**
     * Attempt to close the {@link Connection} and the {@link ExecutorService}
     */
    public void disconnect()
    {
        // Ensuring we're connected
        Preconditions.checkArgument(isConnected(), "Database connection has already been disconnected.");

        // Closing the hikariDataSource
        hikariDataSource.close();

        // Shutting down the pool
        executorService.shutdown();

        try {
            // Removing our ShutdownHook
            Runtime.getRuntime().removeShutdownHook(shutdownThread);
        } catch(IllegalStateException e) {

        }
    }

    /**
     * Find all rows and get back a list of the object provided
     * @param clazz to get data for
     * @param <T> the type to parse to
     * @return all found results
     */
    public <T> List<T> find(Class<T> clazz)
    {
        return find(clazz, new Query());
    }

    /**
     * Find all rows and get back a list of the object provided
     * @param clazz to get data for
     * @param query filter for the query
     * @param <T> the type to parse to
     * @return all found results
     */
    public <T> List<T> find(Class<T> clazz, Query query)
    {
        // Where we'll store our Results
        List<T> results = Lists.newArrayList();

        // Get the Table annotation wrapped in an Optional
        Optional<Table> tableOptional = ReflectionUtil.getTableAnnotation(clazz);

        // Not found so throw an error
        Preconditions.checkArgument(tableOptional.isPresent(), "@Table annotation not found for class " + clazz.getSimpleName() + " when find results.");

        // Get the Table annotation
        Table table = tableOptional.get();

        // Our SQL objects used
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;

        // Wrapping in a SQLException try and catch
        try {
            // Get a new connection
            connection = getConnection();

            // Preparing our statement
            preparedStatement = prepare(connection, String.format("SELECT * FROM %s %s", table.name(), query.toString()), query.getWheres().values().toArray());

            // Execute our PreparedStatement
            resultSet = preparedStatement.executeQuery();

            // Whilst there's results, parse and add to the results
            while (resultSet.next())
            {
                try {
                    // Create a new instance for this class
                    T instance = clazz.newInstance();

                    // Attempt to parse the class
                    populate(instance, resultSet);

                    // Add to the results
                    results.add(instance);
                } catch (InstantiationException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        } catch (SQLException e) {
            // Print the stacktrace
            e.printStackTrace();
        } finally {
            try {
                // Closing our Connection
                if(connection != null && !connection.isClosed())
                    connection.close();

                // Closing our PreparedStatement
                if(preparedStatement != null && !preparedStatement.isClosed())
                    preparedStatement.close();

                // Closing our ResultSet
                if(resultSet != null && !resultSet.isClosed())
                    resultSet.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        // Return our results
        return results;
    }

    /**
     * Verify a {@link uk.co.loonyrules.sql.annotations.Table}'s data on the MySQL server.
     * This will create the table if it doesn't exist, or modify the table depending on the {@link uk.co.loonyrules.sql.enums.ModifyType}
     * @param clazz to verify table integrity for
     * @return whether any data was modified or not.
     */
    public boolean updateTable(Class<?> clazz)
    {
        // Get the Table annotation
        Optional<Table> tableOptional = ReflectionUtil.getTableAnnotation(clazz);

        // Not found so throw an error
        Preconditions.checkArgument(tableOptional.isPresent(), "@Table annotation not found for " + clazz + " when updating Table.");

        // Get the Table annotation
        Table table = tableOptional.get();

        // We're not allowed to do any creation or modifications
        if(!table.create() && table.modifyType() == ModifyType.NONE)
            throw new IllegalArgumentException(String.valueOf("Attempted to update @Table for " + clazz + " even though the properties say we can't."));

        // Get our InformationSchema from our search
        List<Tables> results = find(Tables.class, new Query()
                .where("TABLE_SCHEMA", credentials.getDatabase())
                .where("TABLE_NAME", ReflectionUtil.getTableName(clazz))
                .limit(1));

        // No results so lets Create the Table
        if(results.isEmpty())
        {
            // Whether anything was modified or not
            boolean modified;

            Connection connection = null;
            PreparedStatement preparedStatement = null;

            // Get a new Connection
            try {
                // Get a new Connection
                connection = getConnection();

                // Prepare a new statement
                preparedStatement = prepareCreate(connection, clazz);

                // Execute the prepared statement
                modified = preparedStatement.execute();
            } catch(SQLException e) {
                // Print the stacktrace
                e.printStackTrace();

                // Nothing was modified because an error
                modified = false;
            } finally {
                try {
                    // Closing our Connection
                    if(connection != null && !connection.isClosed())
                        connection.close();

                    // Closing our PreparedStatement
                    if(preparedStatement != null && !preparedStatement.isClosed())
                        preparedStatement.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }

            // Returning the modified state
            return modified;
        }

        // ModifyType is NONE so we'll just ignore
        if(table.modifyType() == ModifyType.NONE)
            return false;

        // Print result count
        System.out.println("Results: " + results.size());

        int i = 0;
        for (Tables result : results) {
            System.out.println(" " + (++i) + " -> " + result.toString());
        }

        // Get the information schema
        return true;
    }

    /**
     * Generate a PreparedStatement with specified data
     * @return the generated PreparedStatement
     */
    private PreparedStatement prepare(Connection connection, String statement, Object... data) throws SQLException
    {
        // Prepare our PreparedStatement
        PreparedStatement preparedStatement = connection.prepareStatement(statement);

        // Iterate through the data
        for(int i = 1; i <= data.length; i++)
        {
            // Get the current Field
            Object object = data[i - 1];

            // Get the Codec for this Type
            Codec codec = Codec.getCodec(object.getClass());

            // Not known so skip
            if(codec == null)
                continue;

            // Encode the data
            codec.encode(preparedStatement, i, object);
        }

        // Return our statement
        return preparedStatement;
    }

    /**
     * Generate a PreparedStatement for creating a Table
     * @param connection to create te PreparedStatement from
     * @param clazz to create the Table from
     * @return the generated PreparedStatement
     * @throws SQLException if an error occurs
     */
    private PreparedStatement prepareCreate(Connection connection, Class<?> clazz) throws SQLException {
        // Get the Table annotation
        Optional<Table> tableOptional = ReflectionUtil.getTableAnnotation(clazz);

        // Not found so throw an error
        Preconditions.checkArgument(tableOptional.isPresent(), "@Table annotation not found for " + clazz + " when preparing Table creation.");

        // Get the Table annotation
        Table table = tableOptional.get();

        // Get the Field's for this Class
        Map<String, Field> fields = ReflectionUtil.getFields(clazz);

        // Where our query string data will be stored
        StringBuilder query = new StringBuilder();

        // Our Primary Field
        Field primaryField = null;

        // Iterate through all Field'entry
        for (Map.Entry<String, Field> entry : fields.entrySet())
        {
            // The Field in question
            Field field = entry.getValue();

            // The name of the Column in the Table
            Column column = ReflectionUtil.getColumnAnnotation(field).get();
            String columnName =  ReflectionUtil.getColumnName(entry.getValue());

            // Get the Codec for this Field
            Codec codec = Codec.getCodec(field.getType());

            // No Codec known, skip!
            if(codec == null)
                continue;

            // Append the data for this Column
            query
                    .append("`")
                    .append(columnName)
                    .append("` ")
                    .append(codec.getSQLType())
                    .append("(")
                    .append(codec.calculateMaxLength(column.maxLength()))
                    .append("), ");

            // If it's the Primary
            Primary primary = field.getAnnotation(Primary.class);

            // Not Primary annotation so ignore
            if(primary == null)
                continue;

            // Ensuring there's not a double Primary annotation
            if(primaryField != null)
                throw new IllegalArgumentException("Found 2 @Primary annotations in " + clazz);

            // Assign primary field
            primaryField = field;

            // Not an auto increment Primary so don't append Query data
            if(!primary.autoIncrement())
                continue;

            // Appending our query data
            query.setLength(query.length() - 2);
            query.append(" AUTO_INCREMENT, ");
        }

        // There was a Primary Field so append some more data
        if(primaryField == null)
            query.setLength(query.length() - 2);
        else query.append("PRIMARY KEY (`").append(ReflectionUtil.getColumnName(primaryField)).append("`)");

        // Build our Query String
        String queryString = String.format("CREATE TABLE IF NOT EXISTS `%s` (%s) ENGINE=InnoDB DEFAULT CHARSET=utf8", table.name(), query.toString());

        // Print debug
        System.out.println("prepareCreate (clazz=" + clazz + ", queryString=" + queryString + ")");

        // Return our PreparedStatement
        return connection.prepareStatement(queryString);
    }

    /**
     * Populate an Object with data from a ResultSet
     * @param object to populate the data into
     * @param resultSet where our data is
     */
    private void populate(Object object, ResultSet resultSet)
    {
        // Getting the Fields for this object
        Map<String, Field> fields = ReflectionUtil.getFields(object.getClass());

        // No known fields so just return
        if(fields == null || fields.isEmpty())
            return;

        // Iterate through ResultSet data as a Map
        for(Map.Entry<String, Object> entry : MapUtil.toMap(resultSet).entrySet())
        {
            // Name of the Column
            String column = entry.getKey();

            // Get the Field associated with the Column name
            Optional<Field> fieldOptional = ReflectionUtil.getColumnField(fields, column);

            // No Field found for this Column name
            if(!fieldOptional.isPresent())
                continue;

            // The Field for this data
            Field field = fieldOptional.get();

            // Get the Codec for this type
            Codec codec = Codec.getCodec(field.getType());

            // No Codec
            if(codec == null)
                continue;

            // Assigning the field's value with the decoded data
            try {
                field.set(object, codec.decode(resultSet, object.getClass(), column));
            } catch (IllegalAccessException | SQLException e) {
                // TODO: LoggerFactory
                System.out.println("Error occurred when decoding " + field);
                e.printStackTrace();
            }
        }
    }

}