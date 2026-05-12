#include <AFMotor.h>
#include <Servo.h>

// Arduino Uno + Adafruit/L293D Motor Shield v1.
// Bluetooth is connected to hardware Serial:
// BT TXD -> Arduino D0/RX
// BT RXD -> Arduino D1/TX through voltage divider
// BT VCC -> 5V
// BT GND -> GND
//
// Important: disconnect BT wires from D0/D1 while uploading this sketch.

const unsigned long SERIAL_BAUD = 9600;

AF_DCMotor frontLeft(1);
AF_DCMotor frontRight(2);
AF_DCMotor rearLeft(3);
AF_DCMotor rearRight(4);

Servo servo1;
const int SERVO1_PIN = 10;
const int SERVO_MIN_ANGLE = 0;
const int SERVO_MAX_ANGLE = 180;
const int SERVO_START_ANGLE = 180;
const int SERVO_STEP_DEGREES = 3;
const unsigned long SERVO_UPDATE_INTERVAL_MS = 10;
const unsigned long SERVO_DETACH_DELAY_MS = 400;
const unsigned long MOTOR_WATCHDOG_TIMEOUT_MS = 500;
const unsigned long TURN_MAX_DURATION_MS = 5000;

int servoCurrentAngle = SERVO_START_ANGLE;
int servoTargetAngle = SERVO_START_ANGLE;
unsigned long lastServoUpdateMs = 0;
unsigned long servoDetachAtMs = 0;
unsigned long lastMotorDebugMs = 0;
unsigned long lastMotorCommandMs = 0;
unsigned long turnStopAtMs = 0;
bool motorsRunning = false;
bool turnActive = false;

char serialBuffer[48];
byte serialIndex = 0;
char latestCommand[48];
bool hasLatestCommand = false;

void setup() {
  Serial.begin(SERIAL_BAUD);

  attachServoIfNeeded();
  servo1.write(servoCurrentAngle);
  servoDetachAtMs = millis() + SERVO_DETACH_DELAY_MS;

  stopAll();
  lastMotorCommandMs = millis();
  Serial.println("READY NO_ACK_MOTORS");
}

void loop() {
  readCommands();
  updateTurn();
  updateMotorWatchdog();
  updateServo();
}

void readCommands() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();

    if (c == '\n' || c == '\r') {
      if (serialIndex > 0) {
        serialBuffer[serialIndex] = '\0';
        strncpy(latestCommand, serialBuffer, sizeof(latestCommand));
        latestCommand[sizeof(latestCommand) - 1] = '\0';
        hasLatestCommand = true;
        serialIndex = 0;
      }
      continue;
    }

    if (serialIndex < sizeof(serialBuffer) - 1) {
      serialBuffer[serialIndex++] = c;
    } else {
      serialIndex = 0;
      Serial.println("ERR overflow");
    }
  }

  if (hasLatestCommand) {
    hasLatestCommand = false;
    handleCommand(latestCommand);
  }
}

void handleCommand(char *command) {
  if (command[0] == '\0') return;

  if (strcmp(command, "STOP") == 0) {
    turnActive = false;
    stopAll();
    lastMotorCommandMs = millis();
    Serial.println("RX STOP");
    return;
  }

  if (command[0] == 'M' && command[1] == ' ') {
    turnActive = false;
    handleMotorCommand(command + 2);
    return;
  }

  if (strncmp(command, "TURN ", 5) == 0) {
    handleTurnCommand(command + 5);
    return;
  }

  if (command[0] == 'S') {
    handleServoCommand(command);
    return;
  }

  Serial.print("ERR ");
  Serial.println(command);
}

void handleTurnCommand(char *args) {
  char *speedToken = strtok(args, " ");
  char *durationToken = strtok(NULL, " ");
  if (speedToken == NULL || durationToken == NULL) {
    Serial.println("ERR TURN");
    return;
  }

  int speed = constrain(atoi(speedToken), -255, 255);
  long requestedDurationMs = atol(durationToken);
  unsigned long durationMs = 0;
  if (requestedDurationMs > 0) {
    durationMs = min((unsigned long)requestedDurationMs, TURN_MAX_DURATION_MS);
  }
  if (speed == 0 || durationMs == 0) {
    turnActive = false;
    stopAll();
    Serial.println("ACK TURN 0");
    return;
  }

  setTurnMotors(speed);
  turnStopAtMs = millis() + durationMs;
  turnActive = true;
  motorsRunning = true;

  Serial.print("ACK TURN ");
  Serial.print(speed);
  Serial.print(' ');
  Serial.println(durationMs);
}

void handleMotorCommand(char *args) {
  int values[4] = {0, 0, 0, 0};
  byte count = 0;

  char *token = strtok(args, " ");
  while (token != NULL && count < 4) {
    values[count++] = constrain(atoi(token), -255, 255);
    token = strtok(NULL, " ");
  }

  if (count != 4) {
    Serial.println("ERR M");
    return;
  }

  setMotor(frontLeft, values[0]);
  setMotor(frontRight, values[1]);
  setMotor(rearLeft, values[2]);
  setMotor(rearRight, values[3]);

  unsigned long now = millis();
  lastMotorCommandMs = now;
  motorsRunning = values[0] != 0 || values[1] != 0 || values[2] != 0 || values[3] != 0;
  if (now - lastMotorDebugMs >= 200) {
    lastMotorDebugMs = now;
    Serial.print("RX M ");
    Serial.print(values[0]);
    Serial.print(' ');
    Serial.print(values[1]);
    Serial.print(' ');
    Serial.print(values[2]);
    Serial.print(' ');
    Serial.println(values[3]);
  }
}

void handleServoCommand(char *command) {
  if (command[0] == 'S' && command[1] >= '0' && command[1] <= '4' && command[2] == '\0') {
    int step = command[1] - '0';
    setServoTarget(map(step, 0, 4, SERVO_MIN_ANGLE, SERVO_MAX_ANGLE));
    return;
  }

  if (strncmp(command, "S1 ", 3) == 0) {
    setServoTarget(atoi(command + 3));
    return;
  }

  Serial.println("ERR S");
}

void setServoTarget(int angle) {
  servoTargetAngle = constrain(angle, SERVO_MIN_ANGLE, SERVO_MAX_ANGLE);
  attachServoIfNeeded();
  servoDetachAtMs = millis() + SERVO_DETACH_DELAY_MS;
  Serial.print("ACK S1 ");
  Serial.println(servoTargetAngle);
}

void updateServo() {
  unsigned long now = millis();
  if (!servo1.attached()) return;

  if (servoCurrentAngle == servoTargetAngle) {
    if (servoDetachAtMs != 0 && (long)(now - servoDetachAtMs) >= 0) {
      servo1.detach();
      servoDetachAtMs = 0;
    }
    return;
  }

  if (now - lastServoUpdateMs < SERVO_UPDATE_INTERVAL_MS) return;
  lastServoUpdateMs = now;

  if (servoCurrentAngle < servoTargetAngle) {
    servoCurrentAngle = min(servoCurrentAngle + SERVO_STEP_DEGREES, servoTargetAngle);
  } else {
    servoCurrentAngle = max(servoCurrentAngle - SERVO_STEP_DEGREES, servoTargetAngle);
  }

  servo1.write(servoCurrentAngle);
  if (servoCurrentAngle == servoTargetAngle) {
    servoDetachAtMs = now + SERVO_DETACH_DELAY_MS;
  }
}

void attachServoIfNeeded() {
  if (servo1.attached()) return;
  servo1.attach(SERVO1_PIN);
  servo1.write(servoCurrentAngle);
}

void updateTurn() {
  if (!turnActive) return;
  if ((long)(millis() - turnStopAtMs) >= 0) {
    turnActive = false;
    stopAll();
    Serial.println("RX TURN DONE");
  }
}

void updateMotorWatchdog() {
  if (turnActive) return;
  if (!motorsRunning) return;
  if (millis() - lastMotorCommandMs > MOTOR_WATCHDOG_TIMEOUT_MS) {
    stopAll();
    Serial.println("RX WATCHDOG STOP");
  }
}

void setTurnMotors(int speed) {
  setMotor(frontLeft, -speed);
  setMotor(frontRight, -speed);
  setMotor(rearLeft, speed);
  setMotor(rearRight, speed);
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
  motorsRunning = false;
}
