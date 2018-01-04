package mq;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.jms.*;

/**
 * {@link org.apache.activemq.ActiveMQSession#setMessageListener(MessageListener)}到底是什么效果?
 *
 * @author skywalker
 */
@Component
public class SessionListener {

    @PostConstruct
    public void receive() {
        ConnectionFactory connectionFactory; // Connection ：JMS 客户端到JMS
        Connection connection = null; // Session： 一个发送或接收消息的线程
        Session session; // Destination ：消息的目的地;消息发送给谁.
        Destination destination; // MessageProducer：消息发送者
        // 构造ConnectionFactory实例对象，此处采用ActiveMq的实现jar
        connectionFactory = new ActiveMQConnectionFactory("admin",
                "admin", "tcp://localhost:61616");
        try {
            connection = connectionFactory.createConnection();
            connection.start();
            session = connection.createSession(Boolean.TRUE, Session.AUTO_ACKNOWLEDGE);

            session.setMessageListener(new MessageListener() {
                @Override
                public void onMessage(Message message) {

                }
            });

            destination = session.createQueue("foo.bar");
            MessageConsumer consumer = session.createConsumer(destination);
            Message message;
            while ((message = consumer.receive()) != null) {
                System.out.println("收到消息: " + message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (null != connection)
                    connection.close();
            } catch (Throwable ignore) {
            }
        }
    }

}
