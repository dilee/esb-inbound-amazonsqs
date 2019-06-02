package org.wso2.carbon.inbound.amazonsqs;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.axis2.builder.Builder;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.builder.SOAPBuilder;
import org.apache.axis2.transport.TransportUtils;
import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericPollingConsumer;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;

public class ATGE_AmazonSQSPollingConsumer extends GenericPollingConsumer  {

	private static final Log logger = LogFactory.getLog(ATGE_AmazonSQSPollingConsumer.class.getName());
    //The time limit to wait when polling queues for messages.
    private int waitTime;
    //Maximum no of messages per poll.
    private int maxNoOfMessage;
    private BasicAWSCredentials credentials;
    private AmazonSQS sqsClient;
    //To check the connection to the Amazon SQS Queue.
    private boolean isConnected;
    //URL of the Amazon SQS Queue from which you want to consume messages.
    private String destination;
    private ReceiveMessageRequest receiveMessageRequest;
    private String messageReceiptHandle;
    //list of attributes need to be receive along with the message.
    private List<String> attributeNames;
    //Content type of the message.
    private String contentType;
    private MessageContext msgCtx;
    //To check whether the message need to be deleted or not from the queue.
    private boolean autoRemoveMessage;
    private String region;

    public ATGE_AmazonSQSPollingConsumer(Properties amazonsqsProperties, String name,
                                    SynapseEnvironment synapseEnvironment, long scanInterval,
                                    String injectingSeq, String onErrorSeq, boolean coordination,
                                    boolean sequential) {
        super(amazonsqsProperties, name, synapseEnvironment, scanInterval, injectingSeq,
                onErrorSeq, coordination, sequential);
        this.injectingSeq = injectingSeq;
        logger.debug("Starting to load the AmazonSQS Inbound Endpoint " + name);
        if (logger.isDebugEnabled()) {
            logger.debug("Starting to load the AmazonSQS Properties for " + name);
        }
        this.destination = properties.getProperty(AmazonSQSConstants.DESTINATION);
        String autoRemoveMessage = properties.getProperty(AmazonSQSConstants.AUTO_REMOVE_MESSAGE);
        this.autoRemoveMessage = !StringUtils.isNotEmpty(autoRemoveMessage) || Boolean.parseBoolean(autoRemoveMessage);
        //AccessKey to interact with Amazon SQS.
        String accessKey = properties.getProperty(AmazonSQSConstants.AMAZONSQS_ACCESSKEY);
        //SecretKey to interact with Amazon SQS.
        String secretKey = properties.getProperty(AmazonSQSConstants.AMAZONSQS_SECRETKEY);
        
//        String env = System.getProperty("env");
        String institution = "AGE";
        String integration = "AGEWSO2-Commons";
        
        if (StringUtils.isEmpty(destination)) {
            throw new SynapseException("URL for the AmazonSQS Queue is empty");
        }
        if (StringUtils.isEmpty(accessKey)) {
            throw new SynapseException("Accesskey is empty");
        }
        if (StringUtils.isEmpty(secretKey)) {
            throw new SynapseException("Secretkey is empty");
        }
        if (StringUtils.isNotEmpty(properties.getProperty(AmazonSQSConstants.AMAZONSQS_SQS_WAIT_TIME))) {
            this.waitTime = Integer.parseInt(properties
                    .getProperty(AmazonSQSConstants.AMAZONSQS_SQS_WAIT_TIME));
        } else {
            this.waitTime = 0;
        }
        if (StringUtils.isNotEmpty(properties
                .getProperty(AmazonSQSConstants.AMAZONSQS_SQS_MAX_NO_OF_MESSAGE))) {
            this.maxNoOfMessage = Integer.parseInt(properties
                    .getProperty(AmazonSQSConstants.AMAZONSQS_SQS_MAX_NO_OF_MESSAGE));
        } else {
            this.maxNoOfMessage = 1;
        }
        if (waitTime < 0 || waitTime > 20) {
            throw new SynapseException("Value " + waitTime
                    + " for parameter WaitTimeSeconds is invalid. Must be >= 0 and <= 20");
        }
        if (maxNoOfMessage < 1 || maxNoOfMessage > 10) {
            throw new SynapseException("Value " + maxNoOfMessage
                    + " for parameter MaxNumberOfMessages is invalid. Must be between 1 and 10");
        }
        if (properties.getProperty(AmazonSQSConstants.ATTRIBUTE_NAMES) != null) {
            this.attributeNames = Arrays.asList(properties.getProperty(AmazonSQSConstants.ATTRIBUTE_NAMES).split(","));
        } else {
            //if attribute names are not define get all the attribute with message.
            this.attributeNames = Arrays.asList(AmazonSQSConstants.ALL);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Loaded the AmazonSQS Parameters with AccessKey : " + accessKey
                    + " , SecretKey : " + secretKey + " , Wait Time : " + waitTime
                    + " , Maximum no of Messages : " + maxNoOfMessage + " , Destination : "
                    + destination + " , AttributeNames : " + attributeNames + " for " + name);
        }
        receiveMessageRequest = new ReceiveMessageRequest(destination);
        receiveMessageRequest.withMaxNumberOfMessages(maxNoOfMessage);
        receiveMessageRequest.withWaitTimeSeconds(waitTime);
        credentials = new BasicAWSCredentials(accessKey, secretKey);
        logger.info("Initialized the AmazonSQS inbound consumer " + name);
    }

    /**
     * Create connection with broker and retrieve the messages. Then inject
     * according to the registered handler.
     */
    public Message poll() {
        if (logger.isDebugEnabled()) {
            logger.debug("Polling AmazonSQS messages for " + name);
        }
        try {
            if (!isConnected) {
//                sqsClient = new AmazonSQSClient(this.credentials);
				sqsClient = AmazonSQSClientBuilder.standard()
						.withCredentials(new AWSStaticCredentialsProvider(this.credentials))
						.withRegion(region).build();
                isConnected = true;
            }
            if (sqsClient == null) {
                logger.error("AmazonSQS Inbound endpoint " + name + " unable to get a connection.");
                isConnected = false;
                return null;
            }
            List<Message> messages;
            receiveMessageRequest=receiveMessageRequest.withAttributeNames(attributeNames);
            receiveMessageRequest=receiveMessageRequest.withMessageAttributeNames(attributeNames);
            messages = sqsClient.receiveMessage(receiveMessageRequest).getMessages();
            if (!messages.isEmpty()) {
                for (Message message : messages) {
                    boolean commitOrRollbacked;
                    if (logger.isDebugEnabled()) {
                        logger.debug("Injecting AmazonSQS message to the sequence : "
                                + injectingSeq + " of " + name);
                    }
                    //Get the content type of the message.
                    if (message.getMessageAttributes().containsKey(AmazonSQSConstants.CONTENT_TYPE)) {
                        contentType = message.getMessageAttributes().get(AmazonSQSConstants.CONTENT_TYPE).getStringValue();
                        if (contentType.trim().equals("") || contentType.equals("null")) {
                            contentType = AmazonSQSConstants.DEFAULT_CONTENT_TYPE;
                        }
                    } else {
                        contentType = properties.getProperty(AmazonSQSConstants.CONTENT_TYPE);
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("Loading the Content-type : " + contentType + " for " + name);
                    }
                    msgCtx = this.createMessageContext();
                    msgCtx.setProperty("MessageId", message.getMessageId());
                    msgCtx.setProperty("ReceiptHandle", message.getReceiptHandle());
                    msgCtx.setProperty("MD5OfBody", message.getMD5OfBody());
                    
                    String messageId=message.getMessageId();
                    String key;
                    MessageAttributeValue value;
                    Map<String, MessageAttributeValue> messageAttributes = message.getMessageAttributes();
                    for (Map.Entry<String, MessageAttributeValue> entry : messageAttributes.entrySet()) {
                        key = entry.getKey();
                        value = entry.getValue();
                        if(StringUtils.isNotEmpty(key) && value != null){
                            if (StringUtils.equals(value.getDataType(), "Binary")) {
                                msgCtx.setProperty(key, value.getBinaryValue().toString());
								if (logger.isDebugEnabled()) {
									logger.debug("key value pair of all message attributes===>" + key + "===>"
											+ value.getStringValue());
								}
                            } else {
                                msgCtx.setProperty(key, value.getStringValue());
                                if (logger.isDebugEnabled()) {
                                	logger.debug("key value pair of all message attributes===>"+key+"===>"+value.getStringValue());
                                }
                            }
                        }
                    }
                    
                    if (logger.isDebugEnabled()) {
                    	logger.debug("Message Id===>"+message.getMessageId()+" Message content===>"+message.toString());
                    }
                    int previousDeliveries = Integer.parseInt(message.getAttributes().get("ApproximateReceiveCount"));
                    if (logger.isDebugEnabled()) {
                    	logger.debug("Message Id===>"+message.getMessageId()+"Got previous delivery count===>"+previousDeliveries);
                    }

                    logger.debug("Message Id===>"+message.getMessageId()+"injecting message===>");

                    String dummyPayload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                                          + "<note>\n"
                                          + "  <to>Tove</to>\n"
                                          + "  <from>Jani</from>\n"
                                          + "  <heading>Reminder</heading>\n"
                                          + "  <body>Test body</body>\n"
                                          + "</note>";

                    commitOrRollbacked = injectMessage(dummyPayload, contentType);

                    if (logger.isDebugEnabled()) {
                    	logger.debug("Message Id===>"+message.getMessageId()+"commitOrRollbacked===>"+commitOrRollbacked);
                    	logger.debug("Message Id===>"+message.getMessageId()+"autoRemoveMessage===>"+autoRemoveMessage);
                    }
                    if (commitOrRollbacked && autoRemoveMessage) {
                    	if (logger.isDebugEnabled()) {
                    		logger.debug("Message Id===>"+message.getMessageId()+"in if condition==>"+this.injectingSeq);
                    	}
                        messageReceiptHandle = message.getReceiptHandle();
                        sqsClient.deleteMessage(new DeleteMessageRequest(destination, messageReceiptHandle));
                        
                    }else{
                    	logger.debug("Message Id===>"+message.getMessageId()+"in else condition");

                    }
                }
            } else {
                return null;
            }
        } catch (AmazonServiceException e) {
        	logger.error(msgCtx.getProperty("MessageId")+" Error Queue Name:       " + this.destination,e);
            throw new SynapseException("Caught an AmazonServiceException, which means your " +
                    "request made it to Amazon SQS, but was rejected with an" +
                    "error response for some reason.", e);
        } catch (AmazonClientException e) {
        	logger.error(msgCtx.getProperty("MessageId")+" Error Queue Name:       " + this.destination,e);
            throw new SynapseException("Caught an AmazonClientException, which means the client" +
                    " encountered a serious internal problem while trying to communicate with SQS, " +
                    "such as not being able to access the network.", e);
        }
        return null;
    }

    /**
     * Inject the message into the sequence.
     */
    @Override
    protected boolean injectMessage(String strMessage, String contentType) {
        AutoCloseInputStream in = new AutoCloseInputStream(new ByteArrayInputStream(strMessage.getBytes()));
        try {
            if (logger.isDebugEnabled()) {
                logger.debug(msgCtx.getProperty("MessageId")+" Processed Custom inbound EP Message of Content-type : " + contentType + " for " + name);
            }
            org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
            Builder builder;
            if (StringUtils.isEmpty(contentType)) {
                logger.warn(msgCtx.getProperty("MessageId")+" Unable to determine content type for message, setting to text/plain for " + name);
                contentType = AmazonSQSConstants.DEFAULT_CONTENT_TYPE;
            }
            int index = contentType.indexOf(';');
            String type = index > 0 ? contentType.substring(0, index) : contentType;
            builder = BuilderUtil.getBuilderFromSelector(type, axis2MsgCtx);
            if (builder == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug(msgCtx.getProperty("MessageId")+" No message builder found for type '" + type +
                            "'. Falling back to SOAP. for" + name);
                }
                builder = new SOAPBuilder();
            }
            OMElement documentElement = builder.processDocument(in, contentType, axis2MsgCtx);
            //Inject the message to the sequence.
            msgCtx.setEnvelope(TransportUtils.createSOAPEnvelope(documentElement));
            if (this.injectingSeq == null || "".equals(this.injectingSeq)) {
                logger.error(msgCtx.getProperty("MessageId")+" Sequence name not specified. Sequence : " + this.injectingSeq + " for " + name);
                return false;
            }
            SequenceMediator seq = (SequenceMediator) this.synapseEnvironment.getSynapseConfiguration()
                    .getSequence(this.injectingSeq);
            if (seq != null) {
            	seq.setErrorHandler(this.onErrorSeq);
                if (logger.isDebugEnabled()) {
                    logger.debug(msgCtx.getProperty("MessageId")+ " injecting message to sequence : " + this.injectingSeq + " of " + name);
                }
                if (!this.synapseEnvironment.injectInbound(msgCtx, seq, this.sequential)) {
                    return false;
                }
            } else {
                logger.error(msgCtx.getProperty("MessageId")+" Sequence: " + this.injectingSeq + " not found for " + name);
            }
            if (isRollback(msgCtx)) {
                return false;
            }
        } catch (Exception e) {
        	logger.error(msgCtx.getProperty("MessageId")+" Error while processing the Amazon SQS Message ",e);
            throw new SynapseException(msgCtx.getProperty("MessageId")+" Error while processing the Amazon SQS Message ", e);
        }
        return true;
    }

    /**
     * Check whether the message is rollbacked or not.
     */
    private boolean isRollback(org.apache.synapse.MessageContext msgCtx) {
        // First check for rollback property from synapse context.
        Object rollbackProp = msgCtx.getProperty(AmazonSQSConstants.SET_ROLLBACK_ONLY);
        if (rollbackProp != null) {
            if ((rollbackProp instanceof Boolean && ((Boolean) rollbackProp))
                    || (rollbackProp instanceof String && Boolean.valueOf((String) rollbackProp))) {
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * Create the message context.
     */
    private MessageContext createMessageContext() {
        MessageContext msgCtx = this.synapseEnvironment.createMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((Axis2MessageContext) msgCtx).getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(UUIDGenerator.getUUID());
        return msgCtx;
    }

    /**
     * Close the connection to the Amazon SQS.
     */
    public void destroy() {
        try {
            if (sqsClient != null) {
                sqsClient.shutdown();
                if (logger.isDebugEnabled()) {
                    logger.debug("The AmazonSQS has been shutdown ! for " + name);
                }
            }
        } catch (Exception e) {
            logger.error("Error while shutdown the AmazonSQS " + name + " " + e.getMessage(), e);
        }
    }

}
