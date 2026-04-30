#include <AFMotor.h>
#include <SoftwareSerial.h>
#include <Servo.h>

// Bluetooth wiring for Arduino Uno:
//   Bluetooth TXD -> Arduino A0
//   Bluetooth RXD -> Arduino A1 through a 5V-to-3.3V divider
//   Bluetooth VCC -> 5V
//   Bluetooth GND -> GND
SoftwareSerial bluetooth(A0, A1); // RX, TX

Servo servo1;
const int SERVO1_PIN = 10; // Motor Shield v1 SERVO_1
const unsigned long SERVO_HOLD_MS = 600;
bool servo1Attached = false;
unsigned long servo1LastCommandAt = 0;

AF_DCMotor frontLeft(1);
AF_DCMotor frontRight(2);
AF_DCMotor rearLeft(3);
AF_DCMotor rearRight(4);

String serialInput;
String bluetoothInput;

void setup() {
  Serial.begin(115200);
  bluetooth.begin(9600);
  setServo1(90);
  stopAll();
}

void loop() {
  readCommands(Serial, serialInput);
  readCommands(bluetooth, bluetoothInput);
  updateServo1();
}

void readCommands(Stream &stream, String &input) {
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
  if (command.length() == 0) {
    return;
  }

  if (command == "STOP") {
    stopAll();
    sendAck(stream, command);
    return;
  }

  if (command.startsWith("S1 ")) {
    int angle = constrain(command.substring(3).toInt(), 0, 180);
    setServo1(angle);
    sendAck(stream, command);
    return;
  }

  if (!command.startsWith("M ")) {
    return;
  }

  int values[4] = {0, 0, 0, 0};
  int valueIndex = 0;
  int start = 2;
  while (valueIndex < 4 && start < command.length()) {
    int space = command.indexOf(' ', start);
    String token = space == -1 ? command.substring(start) : command.substring(start, space);
    values[valueIndex++] = constrain(token.toInt(), -255, 255);
    if (space == -1) {
      break;
    }
    start = space + 1;
  }

  if (valueIndex == 4) {
    setMotor(frontLeft, values[0]);
    setMotor(frontRight, values[1]);
    setMotor(rearLeft, values[2]);
    setMotor(rearRight, values[3]);
    sendAck(stream, command);
  }
}

void sendAck(Stream &stream, const String &command) {
  stream.print("ARD RX: ");
  stream.println(command);
}

void setMotor(AF_DCMotor &motor, int speed) {
  motor.setSpeed(abs(speed));
  if (speed > 0) {
    motor.run(FORWARD);
  } else if (speed < 0) {
    motor.run(BACKWARD);
  } else {
    motor.run(RELEASE);
  }
}

void stopAll() {
  setMotor(frontLeft, 0);
  setMotor(frontRight, 0);
  setMotor(rearLeft, 0);
  setMotor(rearRight, 0);
}

void setServo1(int angle) {
  if (!servo1Attached) {
    servo1.attach(SERVO1_PIN);
    servo1Attached = true;
  }
  servo1.write(angle);
  servo1LastCommandAt = millis();
}

void updateServo1() {
  if (servo1Attached && millis() - servo1LastCommandAt > SERVO_HOLD_MS) {
    servo1.detach();
    servo1Attached = false;
  }
}
