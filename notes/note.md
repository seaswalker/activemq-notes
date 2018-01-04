# 说明

单开此文件的目的是原先的笔记记录与较早之前，现在看起来不是那么尽如人意。在这里用另外一个全新的角度说明一些重要问题。

# 组件结构

首先用最简单的启动消费者的方法开始:

```java
ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(ActiveMQConnection.DEFAULT_USER,
    ActiveMQConnection.DEFAULT_PASSWORD, "tcp://localhost:61616");
Connection connection = connectionFactory.createConnection();
connection.start();
Session session = connection.createSession(Boolean.FALSE, Session.AUTO_ACKNOWLEDGE);
Destination destination = session.createQueue("foo.bar");
MessageConsumer consumer = session.createConsumer(destination);
while (true) {
    consumer.receive();
}
```

用流程图来表示这一过程:

![demo流程](images/demo_flow_chart.png)

进一步，Connection、Session、Consumer以及MessageListener的存储结构如下图：

![存储结构](images/component_structure.png)

## ConsumerId

下面看一下这个消费者ID究竟是个什么东西，创建的入口位于ActiveMQSession的createConsumer方法:

```java
public MessageConsumer createConsumer(Destination destination, String messageSelector, boolean noLocal, MessageListener messageListener) {
    //...
    return new ActiveMQMessageConsumer(this, getNextConsumerId(), activemqDestination, null, messageSelector,
            prefetch, prefetchPolicy.getMaximumPendingMessageLimit(), noLocal, false, isAsyncDispatch(), messageListener);
}
```

由getNextConsumerId()方法创建:

```java
protected ConsumerId getNextConsumerId() {
    return new ConsumerId(info.getSessionId(), consumerIdGenerator.getNextSequenceId());
}
```

consumerIdGenerator其实就是对一个long型数字进行累加。

## ActiveMQDispatcher

从上面的存储结构图中可以看出，Connection同样记录了从ConsumerId到ActiveMQDispatcher的映射，其实在这里**ActiveMQDispatcher就是ActiveMQSession**，类图:

![ActiveMQDispatcher](images/ActiveMQDispatcher.png)

此接口只有一个方法:

```java
void dispatch(MessageDispatch messageDispatch);
```

下面看看被分发的MessageDispatch是个什么东西:

![MessageDispatch](images/MessageDispatch.png)

# 消息是如何接收的

有了上面存储结构说明的铺垫，这一部分将容易理解许多。

TcpTransport的父类TransportThreadSupport的doStart方法创建了一个线程，专门用于接收服务器传来的各种东西，包括Queue消息、Topic消息以及控制命令。

同时MQ维护了一条调用链，用于依次处理收到的东西，关于这条链是如何构成的，参考以前的笔记。

这里我们重点关注调用链的末端--ActiveMQConnection的onCommand方法:

```java
@Override
public void onCommand(final Object o) {
    final Command command = (Command)o;
    if (!closed.get() && command != null) {
        try {
            command.visit(new CommandVisitorAdapter() {
                @Override
                public Response processMessageDispatch(MessageDispatch md) {
                    ActiveMQDispatcher dispatcher = dispatchers.get(md.getConsumerId());
                    dispatcher.dispatch(md);
                    return null;
                }
            }
        }
    }
    //...
}
```

这里的dispatcher便是ActiveMQSession，可以得出一个结论:**一个Session中所有消费者/订阅者的消息均由此Session负责分发**，同时在dispatchers数据结构中，一个ActiveMQSession
会以value的形式存在于多个K-V对中。

ActiveMQSession的dispatch方法:

```java
@Override
public void dispatch(MessageDispatch messageDispatch) {
    executor.execute(messageDispatch);
}
```

execute方法的实现位于ActiveMQSessionExecutor:

```java
void execute(MessageDispatch message) throws InterruptedException {
    if (!session.isSessionAsyncDispatch() && !dispatchedBySessionPool) {
        dispatch(message);
    } else {
        messageQueue.enqueue(message);
        wakeup();
    }
}
```

很明显，这里的分发支持同步和异步两种模式，由ActiveMQConnection的dispatchAsync属性进行控制，默认为true，在Spring中我们可以通过以下配置进行改变:

```xml
<bean id="connectionFactory" class="org.apache.activemq.ActiveMQConnectionFactory">
  <property name="dispatchAsync" value="true" />
</bean>
```

这里我们以较为复杂的异步模式进行说明。

## 消息队列

messageQueue属性在ActiveMQSessionExecutor的构造器中初始化:

```java
ActiveMQSessionExecutor(ActiveMQSession session) {
    this.session = session;
    if (this.session.connection != null 
        && this.session.connection.isMessagePrioritySupported()) {
       this.messageQueue = new SimplePriorityMessageDispatchChannel();
    }else {
        this.messageQueue = new FifoMessageDispatchChannel();
    }
}
```

如果我们进行以下的配置:

```xml
<bean id="connectionFactory" class="org.apache.activemq.ActiveMQConnectionFactory">
    <property name="messagePrioritySupported" value="true" />
</bean>
```

即表示启用消息优先级机制，此时将使用优先级队列进行消息分发，否则使用简单的先进先出队列，默认为关闭。

消息优先级由JMS标准定义，其实就是一个0-9的数字，默认为4.

以ActiveMQTextMessage为例，我们可以通过调用其setPriority方法设置其优先级。

从SimplePriorityMessageDispatchChannel本质上是一个LinkedList数组，**每一个优先级都有一个LinkedList与之对应**。其构造器源码:

```java
private final LinkedList<MessageDispatch>[] lists;
public SimplePriorityMessageDispatchChannel() {
    this.lists = new LinkedList[MAX_PRIORITY];
    for (int i = 0; i < MAX_PRIORITY; i++) {
        lists[i] = new LinkedList<MessageDispatch>();
    }
}
```

出队操作由removeFirst方法完成:

```java
private final MessageDispatch removeFirst() {
    if (this.size > 0) {
        for (int i = MAX_PRIORITY - 1; i >= 0; i--) {
            LinkedList<MessageDispatch> list = lists[i];
            if (!list.isEmpty()) {
                this.size--;
                return list.removeFirst();
            }
        }
    }
    return null;
}
```

优先从高优先级的LinkedList中获取。

## 分发

ActiveMQSessionExecutor.wakeUp方法:

```java
public void wakeup() {
    if (!dispatchedBySessionPool) {
        if (session.isSessionAsyncDispatch()) {
            TaskRunner taskRunner = this.taskRunner;
            if (taskRunner == null) {
                synchronized (this) {
                    if (this.taskRunner == null) {
                        if (!isRunning()) {
                            // stop has been called
                            return;
                        }
                        this.taskRunner = 
                          session.connection.getSessionTaskRunner().createTaskRunner(this,
                                "ActiveMQ Session: " + session.getSessionId());
                    }
                    taskRunner = this.taskRunner;
                }
            }
            taskRunner.wakeup();
        } 
    }
}
```

当我们设置了MessageListener是dispatchedBySessionPool便为true，这一点可以从ActiveMQSession的setMessageListener方法得以证明:

```java
@Override
public void setMessageListener(MessageListener listener) throws JMSException {
    //...
    if (listener != null) {
        executor.setDispatchedBySessionPool(true);
    }
}
```

这可以得出一个很重要的结论:

**MessageListener和其它形式的消费行为是互斥的**，换句话说，一旦MessageListener被设置(对于Session来说)，那么普通的Consumer以及Subscriber都将失效。

### MessageListener

这个地方需要着重说明一点：**Session和Consumer均可以设置MessageListener**，MessageListener和普通的阻塞消费者是两种**互斥**的方式。

### 其它

#### TaskRunner

TaskRunner接口是ActiveMQ对线程执行特定的任务这一行为的抽象。ActiveMQ创建了一个DedicatedTaskRunner对象用于Session阶段的消息分发。

![TaskRunner](images/task_runner.png)

DedicatedTaskRunner的原理从其构造器中便可以看出:

```java
public DedicatedTaskRunner(final Task task, String name, int priority, boolean daemon) {
    this.task = task;
    thread = new Thread(name) {
        @Override
        public void run() {
            try {
                runTask();
            } finally {
                LOG.trace("Run task done: {}", task);
            }
        }
    };
    thread.setDaemon(daemon);
    thread.setName(name);
    thread.setPriority(priority);
    thread.start();
}
```

#### Task

Task接口是ActiveMQ对任务的抽象:

![Task](images/task.png)

这里将任务抽象为一次又一次的事件循环，iterate方法将持续被调用，直到返回false。

#### Session级别分发

核心逻辑位于ActiveMQSessionExecutor的iterate方法:

```java
public boolean iterate() {
    // Deliver any messages queued on the consumer to their listeners.
    for (ActiveMQMessageConsumer consumer : this.session.consumers) {
        if (consumer.iterate()) {
            return true;
        }
    }

    // No messages left queued on the listeners.. so now dispatch messages
    // queued on the session
    MessageDispatch message = messageQueue.dequeueNoWait();
    if (message == null) {
        return false;
    } else {
        dispatch(message);
        return !messageQueue.isEmpty();
    }
}
```

消费者(Subcriber也是消费者)对于消息的处理同样有两种情况:

- MessageListener: 直接由Session分发线程调用其onMessage方法。

- 如果没有设置MessageListener，那么和Session分发的逻辑完全相同，消息首先被加入到一个FIFO或者优先级队列中，之后喜闻乐见的notify我们正在等待消息的消费者。

所以这里Session消息分发的逻辑可以总结为:

- 如果任何一个消费者中尚有没有被消费的消息，那么将这些消息传递给MessageListener。

- 如果第一条不满足，那么将Session收到的消息向下分发给消费者，这里分发的逻辑便是上面提到的。

到这里可能会有疑问: 既然只有当没有设置MessageListener时消息才会被放到队列中，那么上面将未消费的消息传递给MessageListener是什么情况呢？

其实从代码来看，只有满足下列条件时才会直接传递给MessageListener，ActiveMQMessageConsumer.dispatch:

```java
@Override
public void dispatch(MessageDispatch md) {
    if (listener != null && unconsumedMessages.isRunning()) {
        //调用MessageListener.onMessage方法
    }
}
```

猜测需要处理连接已经建立(即已经开始监听消息)，但是消费者还没启动完毕这个时间空隙收到的消息。


