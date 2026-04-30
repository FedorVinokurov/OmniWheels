#include <SoftwareSerial.h>
#include <Servo.h>

// Minimal Bluetooth servo test.
//
// Wiring:
//   BT TXD -> Arduino A0
//   BT RXD -> Arduino A1 through voltage divider
//   BT VCC -> 5V
//   BT GND -> GND
//   Servo signal -> Motor Shield SERVO_1 / Arduino D10
//
// Commands from phone:
//   S1 30
//   S1 90
//   S1 150

SoftwareSerial bluetooth(A0, A1); // RX, TX

const int SERVO1_PIN = 10;
const int CENTER_ANGLE = 90;

Servo servo1;
String input;

void setup() {
  Serial.begin(115200);
  bluetooth.begin(9600);

  servo1.attach(SERVO1_PIN);
  servo1.write(CENTER_ANGLE);

  Serial.println("Servo Bluetooth test ready");
  bluetooth.println("ARD RX: ready");
}

void loop() {
  readCommands(Serial);
  readCommands(bluetooth);
}

void readCommands(Stream &stream) {
  while (stream.available() > 0) {
    char c = stream.read();
    if (c == '\n') {
      handleCommand(stream, input);
      input = "";
    } else if (c != '\r') {
      input += c;
      if (input.length() > 80) {
        input = "";
      }
    }
  }
}

void handleCommand(Stream &stream, String command) {
  command.trim();

  if (command.startsWith("S1 ")) {
    int angle = constrain(command.substring(3).toInt(), 0, 180);
    servo1.write(angle);
    sendAck(stream, command);
  }
}

void sendAck(Stream &stream, const String &command) {
  stream.print("ARD RX: ");
  stream.println(command);
}
