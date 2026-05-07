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
const int SERVO_STEP_DEGREES = 3;
const unsigned long SERVO_UPDATE_INTERVAL_MS = 10;
const unsigned long MOTOR_WATCHDOG_TIMEOUT_MS = 500;

int servoCurrentAngle = 90;
int servoTargetAngle = 90;
unsigned long lastServoUpdateMs = 0;
unsigned long lastMotorDebugMs = 0;
unsigned long lastMotorCommandMs = 0;
bool motorsRunning = false;

char serialBuffer[48];
byte serialIndex = 0;
char latestCommand[48];
bool hasLatestCommand = false;

void setup() {
  Serial.begin(SERIAL_BAUD);

  servo1.attach(SERVO1_PIN);
  servo1.write(servoCurrentAngle);

  stopAll();
  lastMotorCommandMs = millis();
  Serial.println("READY NO_ACK_MOTORS");
}

void loop() {
  readCommands();
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
    stopAll();
    lastMotorCommandMs = millis();
    Serial.println("RX STOP");
    return;
  }

  if (command[0] == 'M' && command[1] == ' ') {
    handleMotorCommand(command + 2);
    return;
  }

  if (command[0] == 'S') {
    handleServoCommand(command);
    return;
  }

  Serial.print("ERR ");
  Serial.println(command);
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
  Serial.print("ACK S1 ");
  Serial.println(servoTargetAngle);
}

void updateServo() {
  unsigned long now = millis();
  if (now - lastServoUpdateMs < SERVO_UPDATE_INTERVAL_MS) return;
  lastServoUpdateMs = now;

  if (servoCurrentAngle == servoTargetAngle) return;

  if (servoCurrentAngle < servoTargetAngle) {
    servoCurrentAngle = min(servoCurrentAngle + SERVO_STEP_DEGREES, servoTargetAngle);
  } else {
    servoCurrentAngle = max(servoCurrentAngle - SERVO_STEP_DEGREES, servoTargetAngle);
  }

  servo1.write(servoCurrentAngle);
}

void updateMotorWatchdog() {
  if (!motorsRunning) return;
  if (millis() - lastMotorCommandMs > MOTOR_WATCHDOG_TIMEOUT_MS) {
    stopAll();
    Serial.println("RX WATCHDOG STOP");
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
  motorsRunning = false;
}
