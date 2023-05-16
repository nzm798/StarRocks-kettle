# Mysql Bulk Loader

> 对 MySQL 表执行流式批量加载。
> 基于 Sven Boden 的 Oracle Bulk Loader 步骤

MySQLBulkLoader扩展了BaseStep和接口StepInterface：

~~~java
public class MySQLBulkLoader extends BaseStep implements StepInterface{
    
    public MySQLBulkLoader( StepMeta stepMeta, StepDataInterface stepDataInterface, int copyNr, TransMeta transMeta,
                            Trans trans ) {
        super( stepMeta, stepDataInterface, copyNr, transMeta, trans );
    }
}
~~~

## 1. BaseSetp类

### 1.1 类说明

> 类可以扩展为实现步骤的实际行处理。
> 
> 实现类可以主要依赖于基类，并且只有三个重要的方法来实现它自己。这三个方法主要是步骤转换的三个执行周期：
> * initialization
> * row processing
> * clean-up

### 1.2 Step Initialization

* 当一个转换开始执行时调用init()方法：

* 每一步都有机会执行一次性初始化任务，例如打开文件或建立数据库连接。
对于从 BaseStep 派生的任何步骤，必须调用 super.init（）以确保正确行为。
如果步骤正确初始化，该方法必须返回 true，如果存在
初始化错误。PDI 将中止转换的执行，以防任何步骤返回 false
初始化。

### 1.3 Row Processing

* 一旦转换开始执行，它就会进入一个循环，在每一步调用processRow（）方法，直到方法返回false。
* 每个步骤通常从输入流中读取一行，更改行结构和字段，以及将该行传递到后续步骤。

* 典型的实现通过调用 getRow（） 来查询传入的输入行，该行阻止并返回行对象 或 null，以防没有更多输入。 如果有输入行，该步骤将执行必要的行处理和调用putRow（）将该行传递到下一步。 如果没有更多的行，则该步骤必须调用 setOutputDone（）和返回false。

> 正式方法必须符合以下规则：
> * 如果该步骤处理完所有行，则该方法必须调用 setOutputDone（）并返回false
> * 如果该步骤没有处理完所有行，该方法会返回true，在这次实例中PDI会再次调用processRow()方法。

### 1.3 Step Clean-Up

* 一但转换完成，PDI会在所用步骤调用dispose()方法。
* 需要采取措施来释放在init（）或后续行处理期间分配的资源。这通常意味着
  清除 StepDataInterface 对象的所有字段，并确保所有打开的文件或连接都正确
  闭。对于从 BaseStep 派生的任何步骤，必须调用 super.dispose（） 以确保正确
  释放。

# Mysql Bulk Loader实现数据传递的流程

## 1. step处理Row信息需要用到processRow（）方法

~~~java
@Override
public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {}
~~~

其中的StepMetaInterface、StepDataInterface接口都需要在定义插件时自己实现。

* StepMetaInterface：主要处理流数据和字段信息
* StepDataInterface：主要是和输出环境有关的信息


~~~java
Object[] r = getRow(); // Get row from input rowset & set row busy!
if ( r == null ) { // no more input to be expected...
  //告诉下一步已经完成数据
  setOutputDone();
  closeOutput();
  return false;
}
~~~

之后调用getRow（）方法获取每一行的数据值

~~~java
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
~~~

在BaseSetp类中会有一个专门的参数first来记录是否为第一行数据。
如果是第一行数据，会将Meta中的数据复制一份到Data中的public ValueMetaInterface[] bulkFormatMeta;
最后调用execute（meta）主要是为了建立与数据库之间的连接，测试连接和生成的语句是否生效。

~~~java
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
~~~

每当Fifo文件中存储了nr行后则关闭流，并且执行executeLoadCommand()方法实现数据的发送到目标。

当没有写入到一定行数时则实现writeRowToBulk( getInputRowMeta(), r )方法实现传输数据的组合和写入到Fifo文件。

## 2. execute()方法调用

execute()主要是执行语句之前的初始工作。

~~~java
public boolean execute( MySQLBulkLoaderMeta meta ) throws KettleException {}
~~~

~~~java
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
~~~

首先，需要创建写入数据的fifo文件，并更改文件的写入权限。

~~~java
// 2) 建立数据库的连接
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
// 连接数据库
if ( getTransMeta().isUsingUniqueConnections() ) { //检查转换是否使用唯一的数据库连接。
  synchronized ( getTrans() ) {
    data.db.connect( getTrans().getTransactionId(), getPartitionID() );
  }
} else {
  data.db.connect( getPartitionID() );
}
logBasic( BaseMessages.getString( PKG, "MySQLBulkLoader.Message.CONNECTED",  data.dbDescription ) );
~~~

主要创建了数据库的连接并对数据库所需信息进行赋值，这一步骤可在StarRocks连接插件中替换成StarRocks连接。

~~~java
// 3) 开始执行加载语句
//
executeLoadCommand();
~~~

开始执行加载语句，因为是第一行数据所以并没有数据的载入所以认为该步骤为测试连接情况。

## 3. executeLoadCommand()加载语句执行

该方法主要实现了加载语句的组合和最后语句的发送执行。

~~~java
private void executeLoadCommand() throws Exception {}
~~~

~~~java
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
~~~

该步骤主要实现的是组装mysql的加载语句。可以更换成StarRocks的Stream Load的导入方式。

~~~
curl --location-trusted -u root: -H "label:123" \
    -H "column_separator:," \
    -H "columns: id, name, score" \
    -T example1.csv -XPUT \
    http://<fe_host>:<fe_http_port>/api/test_db/table1/_stream_load
~~~

~~~java
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
    openFifo.join( 200 );
    if ( openFifo.getState() == Thread.State.TERMINATED ) {
      break;
    }
    try {
      data.sqlRunner.checkExcn();
    } catch ( Exception e ) {
      // We need to open a stream to the fifo to unblock the fifo writer
      // that was waiting for the sqlRunner that now isn't running
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
~~~

该步骤启动执行SQL语句的线程。

使用OpenFifo类打开了fifo文件的写入流，并将流存入Data中，以便在之后向fifo中写入数据使用。

其中，在MySQLBulkLoader.java文件中定义了SqlRunner、OpenFifo类：
* SqlRunner主要执行sql语句执行和错误检查。
  * data.db.execStatement( loadCommand );语句真正实现了sql语句的数据载入功能。

~~~java
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
~~~

* OpenFifo类打开fifo文件流以及检查错误。

~~~java
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
~~~

## 4. writeRowToBulk()写入方法

~~~java
/**
 * Write a row to the fifo file
 * @param rowMeta
 * @param r
 * @throws KettleException
 */
private void writeRowToBulk( RowMetaInterface rowMeta, Object[] r ) throws KettleException {}
~~~

该方法主要实现了CSV格式的数据写入。

~~~java
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
~~~

该步骤通过结合Meta数据，将其数据转换成不同类型，最后转换为Bytes，将其写入fifo数据中。

data.fifoStream就是Data从OpenFifo类中获取。

~~~java
// finally write a newline
//
data.fifoStream.write( data.newline );
if ( ( getLinesOutput() % 5000 ) == 0 ) {
  data.fifoStream.flush();
}
~~~

最后新启一行数据。