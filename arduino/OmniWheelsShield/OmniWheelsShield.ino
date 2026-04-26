#include <AFMotor.h>

AF_DCMotor frontLeft(1);
AF_DCMotor frontRight(2);
AF_DCMotor rearLeft(3);
AF_DCMotor rearRight(4);

String input;

void setup() {
  Serial.begin(115200);
  stopAll();
}

void loop() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n') {
      handleCommand(input);
      input = "";
    } else if (c != '\r') {
      input += c;
    }
  }
}

void handleCommand(String command) {
  command.trim();
  if (command == "STOP") {
    stopAll();
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
  }
}

void setMotor(AF_DCMotor &motor, int speed) {
  int pwm = abs(speed);
  motor.setSpeed(pwm);
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
