package info.kaara.it100.panel

import com.github.kmbulebu.dsc.it100.IT100
import com.github.kmbulebu.dsc.it100.commands.read.*
import com.github.kmbulebu.dsc.it100.commands.util.Key
import com.github.kmbulebu.dsc.it100.commands.write.KeyPressCommand
import com.github.kmbulebu.dsc.it100.commands.write.StatusRequestCommand
import info.kaara.it100.panel.Message.*
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import rx.Subscription
import java.util.concurrent.ConcurrentHashMap

open class IT100WebSocketHandler(val it100: IT100) : TextWebSocketHandler() {

    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    companion object {
        private val log = LoggerFactory.getLogger(IT100WebSocketHandler::class.java)
    }

    @Throws(Exception::class)
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val button = Message.from(message)
        log.info("{}", button)
        it100.send(KeyPressCommand(key(button)))
        it100.send(KeyPressCommand(Key.BREAK))
    }

    private fun key(button: Message.Button): Key {
        return Key.fromAsciiChar(button.button)
    }

    @Throws(Exception::class)
    override fun afterConnectionEstablished(session: WebSocketSession) {
        log.info("Connection established {}", session.id)
        val s = it100.readObservable.subscribe { it ->
            log.info("Message received")
            when (it) {
                is LCDUpdateCommand -> {
                    val lcdUpdate = LCDUpdate(it.lineNumber, it.columnNumber, String(it.asciiData))
                    log.info("LCD update received ${String(it.asciiData)}")
                    session.sendMessage(lcdUpdate.toMessage())
                }
                is LCDCursorCommand -> {
                    log.info("LCD cursor update received ${it.cursorType.name}")
                    val lcdCursor = LCDCursor(it.lineNumber, it.columnNumber, it.cursorType.name)
                    session.sendMessage(lcdCursor.toMessage())
                }
                is LEDStatusCommand -> {
                    log.info("LED {} status changed to {}", it.led, it.ledStatus)
                    val ledStatus = LEDStatus(it.led.number, it.ledStatus.code)
                    session.sendMessage(ledStatus.toMessage())
                }
                is ZoneOpenCommand -> {
                    log.info("Zone {} status changed to ON", it.zone)
                    val ledStatus = LEDStatus(10 + it.zone, 1)
                    session.sendMessage(ledStatus.toMessage())
                }
                is ZoneRestoredCommand -> {
                    log.info("Zone {} status changed to OFF", it.zone)
                    val ledStatus = LEDStatus(10 + it.zone, 0)
                    session.sendMessage(ledStatus.toMessage())
                }
                else -> log.info("Update from IT100 {}", it)

            }
        }
        subscriptions.put(session.id, s)
        it100.send(StatusRequestCommand())
    }

    @Throws(Exception::class)
    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        log.info("Connection closed {}", session.id)
        subscriptions[session.id]?.unsubscribe()
    }
}