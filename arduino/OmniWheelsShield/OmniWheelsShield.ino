#include <AFMotor.h>
#include <SoftwareSerial.h>
#include <ServoTimer2.h>

// Bluetooth:
// BT TXD -> Arduino A0
// BT RXD -> Arduino A1 через делитель
SoftwareSerial bluetooth(A0, A1); 

ServoTimer2 servo1;
const int SERVO1_PIN = 10; 
const int SERVO_MIN_PULSE_US = 750;
const int SERVO_MAX_PULSE_US = 2250;

// Переменные для фильтрации серво
unsigned long lastServoUpdate = 0;
const int SERVO_INTERVAL = 40; // Обновляем не чаще чем раз в 40мс
int lastAngle = -1;
const int ANGLE_THRESHOLD = 2; // Игнорируем поворот меньше чем на 2 градуса

AF_DCMotor frontLeft(1);
AF_DCMotor frontRight(2);
AF_DCMotor rearLeft(3);
AF_DCMotor rearRight(4);

String bluetoothInput = "";

void setup() {
  Serial.begin(115200);
  bluetooth.begin(9600); // Для SoftwareSerial 9600 - самая стабильная скорость

  servo1.attach(SERVO1_PIN);
  setServo1Angle(90);

  stopAll();
  Serial.println("OmniWheels Ready");
}

void loop() {
  // Читаем только Bluetooth (Serial оставим для отладки)
  while (bluetooth.available() > 0) {
    char c = bluetooth.read();
    if (c == '\n') {
      handleCommand(bluetoothInput);
      bluetoothInput = "";
    } else if (c != '\r') {
      bluetoothInput += c;
    }
  }
}

void handleCommand(String command) {
  command.trim();
  if (command.length() == 0) return;

  // Управление Серво: S1 90
  if (command.startsWith("S1 ")) {
    int angle = command.substring(3).toInt();
    angle = constrain(angle, 0, 180);

    unsigned long now = millis();
    // Фильтр: время + значимое изменение угла
    if ((now - lastServoUpdate > SERVO_INTERVAL) && (abs(angle - lastAngle) >= ANGLE_THRESHOLD)) {
      setServo1Angle(angle);
      lastAngle = angle;
      lastServoUpdate = now;
      // Отправляем подтверждение (опционально)
      bluetooth.print("ACK S1 "); bluetooth.println(angle);
    }
  }

  // Управление моторами: M 255 -255 255 -255
  else if (command.startsWith("M ")) {
    parseMotorCommand(command);
  }
  
  else if (command == "STOP") {
    stopAll();
  }
}

void parseMotorCommand(String command) {
  int values[4] = {0, 0, 0, 0};
  int valueIndex = 0;
  int start = 2;

  while (valueIndex < 4 && start < command.length()) {
    int space = command.indexOf(' ', start);
    String token = (space == -1) ? command.substring(start) : command.substring(start, space);
    values[valueIndex++] = constrain(token.toInt(), -255, 255);
    if (space == -1) break;
    start = space + 1;
  }

  if (valueIndex == 4) {
    setMotor(frontLeft, values[0]);
    setMotor(frontRight, values[1]);
    setMotor(rearLeft, values[2]);
    setMotor(rearRight, values[3]);
  }
}

void setMotor(AF_DCMotor &motor, int speed) {
  motor.setSpeed(abs(speed));
  if (speed > 0) motor.run(FORWARD);
  else if (speed < 0) motor.run(BACKWARD);
  else motor.run(RELEASE);
}

void stopAll() {
  setMotor(frontLeft, 0);
  setMotor(frontRight, 0);
  setMotor(rearLeft, 0);
  setMotor(rearRight, 0);
}

void setServo1Angle(int angle) {
  int pulse = map(angle, 0, 180, SERVO_MIN_PULSE_US, SERVO_MAX_PULSE_US);
  servo1.write(pulse);
}