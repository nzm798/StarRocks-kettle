/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2002-2022 by Hitachi Vantara : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.steps.mysqlbulkloader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.util.Utils;
import org.pentaho.di.core.DBCache;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.plugins.DatabasePluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaDate;
import org.pentaho.di.core.row.value.ValueMetaNumber;
import org.pentaho.di.core.util.StreamLogger;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

/**
 * Performs a streaming bulk load to a MySQL table.
 *
 * Based on Sven Boden's Oracle Bulk Loader step
 *
 * @author matt
 * @since 14-apr-2009
 */
public class MySQLBulkLoader extends BaseStep implements StepInterface {
  private static Class<?> PKG = MySQLBulkLoaderMeta.class; // for i18n purposes, needed by Translator2!!

  private MySQLBulkLoaderMeta meta;
  private MySQLBulkLoaderData data;
  private final long threadWaitTime = 300000;
  private final String threadWaitTimeText = "5min";

  public MySQLBulkLoader( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
      Trans trans ) {
    super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
  }

  public boolean execute( MySQLBulkLoaderMeta meta ) throws KettleException {
    Runtime rt = Runtime.getRuntime();

    try {
      // 1) Create the FIFO file using the "mkfifo" command...
      // Make sure to log all the possible output, also from STDERR
      //1） 使用“mkfifo”命令创建FIFO文件...
      //确保记录所有可能的输出，也来自 STDERR
      //使用当前的变量空间替换字符串名
      data.fifoFilename = environmentSubstitute( meta.getFifoFileName() );

      File fifoFile = new File( data.fifoFilename );
      if ( !fifoFile.exists() ) {
        // MKFIFO!
        //
        String mkFifoCmd = "mkfifo " + data.fifoFilename;
        //
        logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.CREATINGFIFO",  data.dbDescription, mkFifoCmd ) );
        // linux 中创建fifo文件，获得Process可用来操控进程
        Process mkFifoProcess = rt.exec( mkFifoCmd );
        StreamLogger errorLogger = new StreamLogger( log, mkFifoProcess.getErrorStream(), "mkFifoError" ); //获取子进程错误流
        StreamLogger outputLogger = new StreamLogger( log, mkFifoProcess.getInputStream(), "mkFifoOuptut" ); //获取子进程输出流
        new Thread( errorLogger ).start();
        new Thread( outputLogger ).start();
        // 当前进程等待
        int result = mkFifoProcess.waitFor();
        if ( result != 0 ) {
          throw new Exception( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORFIFORC", result, mkFifoCmd ) );
        }

        String chmodCmd = "chmod 666 " + data.fifoFilename;
        logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.SETTINGPERMISSIONSFIFO",  data.dbDescription, chmodCmd ) );
        Process chmodProcess = rt.exec( chmodCmd );
        errorLogger = new StreamLogger( log, chmodProcess.getErrorStream(), "chmodError" );
        outputLogger = new StreamLogger( log, chmodProcess.getInputStream(), "chmodOuptut" );
        new Thread( errorLogger ).start();
        new Thread( outputLogger ).start();
        result = chmodProcess.waitFor();
        if ( result != 0 ) {
          throw new Exception( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORFIFORC", result, chmodCmd ) );
        }
      }

      // 2) Make a connection to MySQL for sending SQL commands
      // (Also, we need a clear cache for getting up-to-date target metadata)
      DBCache.getInstance().clear( meta.getDatabaseMeta().getName() );
      if ( meta.getDatabaseMeta() == null ) {
        logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Init.ConnectionMissing", getStepname() ) );
        return false;
      }
      data.db = new Database( this, meta.getDatabaseMeta() );
      data.db.shareVariablesWith( this );
      PluginInterface dbPlugin =
          PluginRegistry.getInstance().getPlugin( DatabasePluginType.class, meta.getDatabaseMeta().getDatabaseInterface() );
      data.dbDescription = ( dbPlugin != null ) ? dbPlugin.getDescription() : BaseMessages.getString( PKG, "MySQLBulkLoader.UnknownDB" );

      // Connect to the database
      if ( getTransMeta().isUsingUniqueConnections() ) { //检查转换是否使用唯一的数据库连接。
        synchronized ( getTrans() ) {
          data.db.connect( getTrans().getTransactionId(), getPartitionID() );
        }
      } else {
        data.db.connect( getPartitionID() );
      }

      logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.CONNECTED",  data.dbDescription ) );

      // 3) Now we are ready to run the load command...
      //
      executeLoadCommand();
    } catch ( Exception ex ) {
      throw new KettleException( ex );
    }

    return true;
  }

  /**
   * 执行加载命令，组合执行的Mysql语句
   * load data local infile 'D:/my_user_info.txt' into table user_info
   * CHARACTER SET utf8 -- 可选，指定导入文件的编码，避免中文乱码问题。假如这里文件 my_user_info.txt 的编码为 gbk，那么这里编码就应该设为 gbk 了
   * FIELDS TERMINATED BY '||' -- 字段分隔符，每个字段(列)以什么字符分隔，默认是 \t
   * 	OPTIONALLY ENCLOSED BY '' -- 文本限定符，每个字段被什么字符包围，默认是空字符
   * 	ESCAPED BY '\\' -- 转义符，默认是 \
   * LINES TERMINATED BY '\n' -- 记录分隔符，如字段本身也含\n，那么应先去除，否则load data 会误将其视作另一行记录进行导入
   * (id, name, age, address, create_date) -- 每一行文本按顺序对应的表字段，建议不要省略
   * @throws Exception
   */
  private void executeLoadCommand() throws Exception {

    String loadCommand = "";
    loadCommand +=
        "LOAD DATA " + ( meta.isLocalFile() ? "LOCAL" : "" ) + " INFILE '"
            + environmentSubstitute( meta.getFifoFileName() ) + "' ";
    if ( meta.isReplacingData() ) {
      loadCommand += "REPLACE ";
    } else if ( meta.isIgnoringErrors() ) {
      loadCommand += "IGNORE ";
    }
    loadCommand += "INTO TABLE " + data.schemaTable + " ";
    if ( !Utils.isEmpty( meta.getEncoding() ) ) {
      loadCommand += "CHARACTER SET " + meta.getEncoding() + " ";
    }
    String delStr = meta.getDelimiter();
    if ( "\t".equals( delStr ) ) {
      delStr = "\\t";
    }

    loadCommand += "FIELDS TERMINATED BY '" + delStr + "' ";
    if ( !Utils.isEmpty( meta.getEnclosure() ) ) {
      loadCommand += "OPTIONALLY ENCLOSED BY '" + meta.getEnclosure() + "' ";
    }
    loadCommand +=
        "ESCAPED BY '" + meta.getEscapeChar() + ( "\\".equals( meta.getEscapeChar() ) ? meta.getEscapeChar() : "" )
            + "' ";

    // 设置列名称
    loadCommand += "(";
    for ( int cnt = 0; cnt < meta.getFieldTable().length; cnt++ ) {
      loadCommand += meta.getDatabaseMeta().quoteField( meta.getFieldTable()[cnt] ); //返回字段名称
      if ( cnt < meta.getFieldTable().length - 1 ) {
        loadCommand += ",";
      }
    }
    //CR：特定操作系统的回车符
    loadCommand += ");" + Const.CR;

    logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.STARTING",  data.dbDescription, loadCommand ) );

    data.sqlRunner = new SqlRunner( data, loadCommand );
    data.sqlRunner.start();

    // Ready to start writing rows to the FIFO file now...
    //
    if ( !Const.isWindows() ) {
      logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.OPENFIFO",  data.fifoFilename ) );
      OpenFifo openFifo = new OpenFifo( data.fifoFilename, 1000 );
      openFifo.start();

      // Wait for either the sql statement to throw an error or the
      // fifo writer to throw an error
      while ( true ) {
        //当前线程执行时插入openFifo
        openFifo.join( 200 );
        // 当线程完成时处于终止状态
        if ( openFifo.getState() == Thread.State.TERMINATED ) {
          break;
        }

        try {
          data.sqlRunner.checkExcn();
        } catch ( Exception e ) {
          // We need to open a stream to the fifo to unblock the fifo writer
          // that was waiting for the sqlRunner that now isn't running
          // 在UNIX/Linux系统中，当有进程以写模式打开一个FIFO文件时，如果没有其他进程以读模式打开该文件，写入操作将被阻塞。
          // 因此，在这种情况下，通过打开一个读模式的输入流来读取FIFO文件，可以解除FIFO写入器的阻塞状态。
          new BufferedInputStream( new FileInputStream( data.fifoFilename ) ).close();
          openFifo.join();
          logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORFIFO" ) );
          logError( "" );
          throw e;
        }

        try {
          openFifo.checkExcn();
        } catch ( Exception e ) {
          throw e;
        }
      }
      data.fifoStream = openFifo.getFifoStream();
    }

  }

  @Override
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {
    meta = (MySQLBulkLoaderMeta) smi;
    data = (MySQLBulkLoaderData) sdi;

    try {
      Object[] r = getRow(); // Get row from input rowset & set row busy!
      if ( r == null ) { // no more input to be expected...
        //告诉下一步已经完成数据
        setOutputDone();

        closeOutput();

        return false;
      }
      // 如果为 true，则正在处理的行是第一行
      if ( first ) {
        first = false;

        // Cache field indexes.
        //data.keynrs是字段⚪数据所对应的index
        data.keynrs = new int[meta.getFieldStream().length];
        for ( int i = 0; i < data.keynrs.length; i++ ) {
          data.keynrs[i] = getInputRowMeta().indexOfValue( meta.getFieldStream()[i] );
        }
        //除了Mysql中的date和数值类型，其他的都是String类型
        data.bulkFormatMeta = new ValueMetaInterface[data.keynrs.length];
        for ( int i = 0; i < data.keynrs.length; i++ ) {
          //通过特定的index获得value meta
          ValueMetaInterface sourceMeta = getInputRowMeta().getValueMeta( data.keynrs[i] );
          //判断是否为日期
          if ( sourceMeta.isDate() ) {
            if ( meta.getFieldFormatType()[i] == MySQLBulkLoaderMeta.FIELD_FORMAT_TYPE_DATE ) {
              data.bulkFormatMeta[i] = data.bulkDateMeta.clone();
            } else if ( meta.getFieldFormatType()[i] == MySQLBulkLoaderMeta.FIELD_FORMAT_TYPE_TIMESTAMP ) {
              data.bulkFormatMeta[i] = data.bulkTimestampMeta.clone(); // default to timestamp
            }
          } else if ( sourceMeta.isNumeric()
              && meta.getFieldFormatType()[i] == MySQLBulkLoaderMeta.FIELD_FORMAT_TYPE_NUMBER ) {
            data.bulkFormatMeta[i] = data.bulkNumberMeta.clone();
          }

          if ( data.bulkFormatMeta[i] == null && !sourceMeta.isStorageBinaryString() ) {
            data.bulkFormatMeta[i] = sourceMeta.clone();
          }
        }

        // execute the client statement...
        // 当是第一行时，执行客户端语句，主要对fifo文件的建立和数据库的连接，以及对其进行测试
        execute( meta );
      }

      // Every nr of rows we re-start the bulk load process to allow indexes etc to fit into the MySQL server memory
      // Performance could degrade if we don't do this.
      //每 nr 行我们重新启动批量加载过程，以允许索引等适合 MySQL 服务器内存
      //如果我们不这样做，性能可能会下降。
      //当Fifo文件写入一定的行数时，就将数据传输数据库
      if ( data.bulkSize > 0 && getLinesOutput() > 0 && ( getLinesOutput() % data.bulkSize ) == 0 ) {
        //当多少行之后关闭原有的输出文件
        closeOutput();
        //拼接执行语句
        executeLoadCommand();
      }

      writeRowToBulk( getInputRowMeta(), r );
      putRow( getInputRowMeta(), r );
      //递增写入输出目标（数据库、文件、套接字等）的行数。
      incrementLinesOutput();

      return true;
    } catch ( Exception e ) {
      logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Log.ErrorInStep" ), e );
      setErrors( 1 );
      stopAll();
      setOutputDone(); // signal end to receiver(s)
      return false;
    }
  }

  private void closeOutput() throws Exception {

    if ( data.fifoStream != null ) {
      // Close the fifo file...
      //
      data.fifoStream.close();
      data.fifoStream = null;
    }

    if ( data.sqlRunner != null ) {

      // wait for the INSERT statement to finish and check for any error and/or warning...
      logDebug( "Waiting up to " + this.threadWaitTimeText + " for the MySQL load command thread to finish processing." ); // no requirement for NLS debug messages
      data.sqlRunner.join( this.threadWaitTime );
      SqlRunner sqlRunner = data.sqlRunner;
      data.sqlRunner = null;
      sqlRunner.checkExcn();
    }
  }

  /**
   * Write a row to the fifo file
   * @param rowMeta
   * @param r
   * @throws KettleException
   */
  private void writeRowToBulk( RowMetaInterface rowMeta, Object[] r ) throws KettleException {

    try {
      // So, we have this output stream to which we can write CSV data to.
      // Basically, what we need to do is write the binary data (from strings to it as part of this proof of concept)
      //
      // The data format required is essentially:
      //因此，我们有可以将 CSV 数据写入的输出流。
      //基本上，我们需要做的是编写二进制数据（从字符串到它作为这个概念证明的一部分）
      //所需的数据格式基本上是：
      //
      for ( int i = 0; i < data.keynrs.length; i++ ) {
        if ( i > 0 ) {
          // Write a separator
          // 写入分隔符
          data.fifoStream.write( data.separator );
        }

        int index = data.keynrs[i];
        //要填数据的类型元数据
        ValueMetaInterface valueMeta = rowMeta.getValueMeta( index );
        //valueData行中要填的数据
        Object valueData = r[index];

        if ( valueData == null ) {
          data.fifoStream.write( "NULL".getBytes() );
        } else {
          // Write the data in the right format...
          switch ( valueMeta.getType() ) {
            case ValueMetaInterface.TYPE_STRING:
              data.fifoStream.write( data.quote );
              if ( valueMeta.isStorageBinaryString() //检查是否存储为二进制字符串
                  && meta.getFieldFormatType()[i] == MySQLBulkLoaderMeta.FIELD_FORMAT_TYPE_OK ) {
                // We had a string, just dump it back.
                data.fifoStream.write( (byte[]) valueData );
              } else {
                String string = valueMeta.getString( valueData );
                if ( string != null ) {
                  if ( meta.getFieldFormatType()[i] == MySQLBulkLoaderMeta.FIELD_FORMAT_TYPE_STRING_ESCAPE ) {
                    string = Const.replace( string, meta.getEscapeChar(), meta.getEscapeChar() + meta.getEscapeChar() );
                    string = Const.replace( string, meta.getEnclosure(), meta.getEscapeChar() + meta.getEnclosure() );
                  }
                  data.fifoStream.write( string.getBytes() );
                }
              }
              data.fifoStream.write( data.quote );
              break;
            case ValueMetaInterface.TYPE_INTEGER:
              if ( valueMeta.isStorageBinaryString() && data.bulkFormatMeta[i] == null ) {
                data.fifoStream.write( valueMeta.getBinaryString( valueData ) );
              } else {
                //将提供的数据转换为Integer
                Long integer = valueMeta.getInteger( valueData );
                if ( integer != null ) {
                  data.fifoStream.write( data.bulkFormatMeta[i].getString( integer ).getBytes() );
                }
              }
              break;
            case ValueMetaInterface.TYPE_DATE:
              if ( valueMeta.isStorageBinaryString() && data.bulkFormatMeta[i] == null ) {
                data.fifoStream.write( valueMeta.getBinaryString( valueData ) );
              } else {
                Date date = valueMeta.getDate( valueData );
                if ( date != null ) {
                  data.fifoStream.write( data.bulkFormatMeta[i].getString( date ).getBytes() );
                }
              }
              break;
            case ValueMetaInterface.TYPE_BOOLEAN:
              if ( valueMeta.isStorageBinaryString() && data.bulkFormatMeta[i] == null ) {
                data.fifoStream.write( valueMeta.getBinaryString( valueData ) );
              } else {
                Boolean b = valueMeta.getBoolean( valueData );
                if ( b != null ) {
                  data.fifoStream.write( data.bulkFormatMeta[i].getString( b ).getBytes() );
                }
              }
              break;
            case ValueMetaInterface.TYPE_NUMBER:
              if ( valueMeta.isStorageBinaryString() && data.bulkFormatMeta[i] == null ) {
                data.fifoStream.write( (byte[]) valueData );
              } else {
                /**
                 * If this is the first line, reset default conversion mask for Number type (#.#;-#.#).
                 * This will make conversion mask to be calculated according to meta data (length, precision).
                 *
                 * http://jira.pentaho.com/browse/PDI-11421
                 */
                if ( getLinesWritten() == 0 ) {
                  data.bulkFormatMeta[i].setConversionMask( null );
                }

                Double d = valueMeta.getNumber( valueData );
                if ( d != null ) {
                  data.fifoStream.write( data.bulkFormatMeta[i].getString( d ).getBytes() );
                }
              }
              break;
            case ValueMetaInterface.TYPE_BIGNUMBER:
              if ( valueMeta.isStorageBinaryString() && data.bulkFormatMeta[i] == null ) {
                data.fifoStream.write( (byte[]) valueData );
              } else {
                BigDecimal bn = valueMeta.getBigNumber( valueData );
                if ( bn != null ) {
                  data.fifoStream.write( data.bulkFormatMeta[i].getString( bn ).getBytes() );
                }
              }
              break;
            default:
              break;
          }
        }
      }

      // finally write a newline
      //
      data.fifoStream.write( data.newline );

      if ( ( getLinesOutput() % 5000 ) == 0 ) {
        data.fifoStream.flush();
      }
    } catch ( IOException e ) {
      // If something went wrong with writing to the fifo, get the underlying error from MySQL
      try {
        logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.IOERROR", this.threadWaitTimeText ) );
        try {
          data.sqlRunner.join( this.threadWaitTime );
        } catch ( InterruptedException ex ) {
          // Ignore errors
        }
        data.sqlRunner.checkExcn();
      } catch ( Exception loadEx ) {
        throw new KettleException( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORSERIALIZING" ), loadEx );
      }

      // MySQL didn't finish, throw the generic "Pipe" exception.
      throw new KettleException( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORSERIALIZING" ), e );

    } catch ( Exception e2 ) {
      // Null pointer exceptions etc.
      throw new KettleException( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.ERRORSERIALIZING" ), e2 );
    }
  }

  protected void verifyDatabaseConnection() throws KettleException {
    // Confirming Database Connection is defined.
    if ( meta.getDatabaseMeta() == null ) {
      throw new KettleException( BaseMessages.getString( PKG, "MySQLBulkLoaderMeta.GetSQL.NoConnectionDefined" ) );
    }
  }

  @Override
  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (MySQLBulkLoaderMeta) smi;
    data = (MySQLBulkLoaderData) sdi;

    if ( super.init( smi, sdi ) ) {

      // Confirming Database Connection is defined.
      try {
        verifyDatabaseConnection();
      } catch ( KettleException ex ) {
        logError( ex.getMessage() );
        return false;
      }

      if ( Utils.isEmpty( meta.getEnclosure() ) ) {
        data.quote = new byte[] {};
      } else {
        data.quote = environmentSubstitute( meta.getEnclosure() ).getBytes();
      }
      if ( Utils.isEmpty( meta.getDelimiter() ) ) {
        data.separator = "\t".getBytes();
      } else {
        data.separator = environmentSubstitute( meta.getDelimiter() ).getBytes();
      }
      data.newline = Const.CR.getBytes();

      String realEncoding = environmentSubstitute( meta.getEncoding() );
      data.bulkTimestampMeta = new ValueMetaDate( "timestampMeta" );
      data.bulkTimestampMeta.setConversionMask( "yyyy-MM-dd HH:mm:ss" );
      data.bulkTimestampMeta.setStringEncoding( realEncoding );

      data.bulkDateMeta = new ValueMetaDate( "dateMeta" );
      data.bulkDateMeta.setConversionMask( "yyyy-MM-dd" );
      data.bulkDateMeta.setStringEncoding( realEncoding );

      data.bulkNumberMeta = new ValueMetaNumber( "numberMeta" );
      data.bulkNumberMeta.setConversionMask( "#.#" );
      data.bulkNumberMeta.setGroupingSymbol( "," );
      data.bulkNumberMeta.setDecimalSymbol( "." );
      data.bulkNumberMeta.setStringEncoding( realEncoding );

      data.bulkSize = Const.toLong( environmentSubstitute( meta.getBulkSize() ), -1L );

      // Schema-table combination...
      data.schemaTable =
          meta.getDatabaseMeta().getQuotedSchemaTableCombination( environmentSubstitute( meta.getSchemaName() ),
              environmentSubstitute( meta.getTableName() ) );

      return true;
    }
    return false;
  }

  @Override
  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {
    meta = (MySQLBulkLoaderMeta) smi;
    data = (MySQLBulkLoaderData) sdi;

    // Close the output streams if still needed.
    //
    try {
      if ( data.fifoStream != null ) {
        data.fifoStream.close();
      }

      // Stop the SQL execution thread
      //
      if ( data.sqlRunner != null ) {
        data.sqlRunner.join();
        data.sqlRunner = null;
      }
      // Release the database connection
      //
      if ( data.db != null ) {
        data.db.disconnect();
        data.db = null;
      }

      // remove the fifo file...
      //
      try {
        if ( data.fifoFilename != null ) {
          new File( data.fifoFilename ).delete();
        }
      } catch ( Exception e ) {
        logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.UNABLETODELETE", data.fifoFilename ), e );
      }
    } catch ( Exception e ) {
      setErrors( 1L );
      logError( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.UNEXPECTEDERRORCLOSING" ), e );
    }

    super.dispose( smi, sdi );
  }

  // Class to try and open a writer to a fifo in a different thread.
  // Opening the fifo is a blocking call, so we need to check for errors
  // after a small waiting period
  static class OpenFifo extends Thread {
    private BufferedOutputStream fifoStream = null;
    private Exception ex;
    private String fifoName;
    private int size;

    OpenFifo( String fifoName, int size ) {
      this.fifoName = fifoName;
      this.size = size;
    }

    @Override
    public void run() {
      try {
        //用于后续向fifo文件写入数据
        fifoStream = new BufferedOutputStream( new FileOutputStream( OpenFifo.this.fifoName ), this.size );
      } catch ( Exception ex ) {
        this.ex = ex;
      }
    }

    void checkExcn() throws Exception {
      // This is called from the main thread context to rethrow any saved
      // excn.
      if ( ex != null ) {
        throw ex;
      }
    }

    BufferedOutputStream getFifoStream() {
      return fifoStream;
    }
  }

  static class SqlRunner extends Thread {
    private MySQLBulkLoaderData data;

    private String loadCommand;

    private Exception ex;

    SqlRunner( MySQLBulkLoaderData data, String loadCommand ) {
      this.data = data;
      this.loadCommand = loadCommand;
    }

    @Override
    public void run() {
      try {
        data.db.execStatement( loadCommand );
      } catch ( Exception ex ) {
        this.ex = ex;
      }
    }

    void checkExcn() throws Exception {
      // This is called from the main thread context to rethrow any saved
      // excn.
      if ( ex != null ) {
        throw ex;
      }
    }
  }
}
