package info.kaara.it100.panel

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.github.kmbulebu.dsc.it100.IT100
import com.github.kmbulebu.dsc.it100.commands.read.*
import com.github.kmbulebu.dsc.it100.commands.util.Key
import com.github.kmbulebu.dsc.it100.commands.write.KeyPressCommand
import com.github.kmbulebu.dsc.it100.commands.write.StatusRequestCommand
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.*
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import rx.Subscription
import java.util.concurrent.ConcurrentHashMap

abstract class Message {
    companion object {
        val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())
    }

    fun toMessage(): WebSocketMessage<String> {
        return TextMessage(mapper.writeValueAsString(this))
    }
}

class Button(val button: Char) : Message() {
    companion object {
        fun from(message: TextMessage): Button {
            return mapper.readValue(message.payload, Button::class.java)
        }
    }
}

class LCDUpdate(val line: Int, val column: Int, val text: String) : Message()
class LCDCursor(val line: Int, val column: Int, val cursor: String) : Message()
class LEDStatus(val led: Int, val status: Int) : Message()

@Configuration
@EnableWebSocket
open class WebSocketConfig : WebSocketConfigurer {

    @Autowired
    lateinit var it100WebSocketHandler: WebSocketHandler

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(it100WebSocketHandler, "/it100");
    }

    @Bean
    open fun it100WebSocketHandler(it100: IT100): WebSocketHandler {
        return IT100WebSocketHandler(it100);
    }

}

open class IT100WebSocketHandler(val it100: IT100) : TextWebSocketHandler() {

    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    companion object {
        private val log = LoggerFactory.getLogger(IT100WebSocketHandler::class.java)
    }

    @Throws(Exception::class)
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val button = Button.from(message)
        log.info("{}", button)
        it100.send(KeyPressCommand(key(button)))
        it100.send(KeyPressCommand(Key.BREAK))
    }

    private fun key(button: Button): Key {
        return Key.fromAsciiChar(button.button)
    }

    @Throws(Exception::class)
    override fun afterConnectionEstablished(session: WebSocketSession) {
        log.info("Connection established {}", session.id)
        val s = it100.readObservable.subscribe { it ->
            log.info("Message received")
            if (it is LCDUpdateCommand) {
                val lcdUpdate: LCDUpdate = LCDUpdate(it.lineNumber, it.columnNumber, String(it.asciiData))
                log.info("LCD update received ${String(it.asciiData)}")
                session.sendMessage(lcdUpdate.toMessage())
            } else if (it is LCDCursorCommand) {
                val lcdCursor: LCDCursor = LCDCursor(it.lineNumber, it.columnNumber, it.cursorType.toString())
                session.sendMessage(lcdCursor.toMessage())
            } else if (it is LEDStatusCommand) {
                log.info("LED {} status changed to {}", it.led, it.ledStatus)
                val ledStatus: LEDStatus = LEDStatus(it.led.number, it.ledStatus.code)
                session.sendMessage(ledStatus.toMessage())
            } else if (it is ZoneOpenCommand) {
                log.info("Zone {} status changed to ON", it.zone)
                val ledStatus: LEDStatus = LEDStatus(10 + it.zone, 1)
                session.sendMessage(ledStatus.toMessage())
            } else if (it is ZoneRestoredCommand) {
                log.info("Zone {} status changed to OFF", it.zone)
                val ledStatus: LEDStatus = LEDStatus(10 + it.zone, 0)
                session.sendMessage(ledStatus.toMessage())
            } else {
                log.info("Update from IT100 {}", it)
            }
        }
        subscriptions.put(session.id, s)
        it100.send(StatusRequestCommand())
    }

    @Throws(Exception::class)
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        log.info("Connection closed {}", session.id)
        subscriptions[session.id]!!.unsubscribe()
    }
}