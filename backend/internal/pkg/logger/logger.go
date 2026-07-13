package logger

import (
	"encoding/json"
	"log"
	"os"
	"time"
)

type Level string

const (
	LevelInfo  Level = "info"
	LevelWarn  Level = "warn"
	LevelError Level = "error"
	LevelDebug Level = "debug"
)

type Logger struct {
	service string
	out     *log.Logger
}

func New(service string) *Logger {
	return &Logger{
		service: service,
		out:     log.New(os.Stdout, "", 0),
	}
}

func (l *Logger) Info(msg string, fields map[string]any)  { l.log(LevelInfo, msg, fields) }
func (l *Logger) Warn(msg string, fields map[string]any)  { l.log(LevelWarn, msg, fields) }
func (l *Logger) Error(msg string, fields map[string]any) { l.log(LevelError, msg, fields) }
func (l *Logger) Debug(msg string, fields map[string]any) { l.log(LevelDebug, msg, fields) }

func (l *Logger) log(level Level, msg string, fields map[string]any) {
	if fields == nil {
		fields = map[string]any{}
	}
	fields["ts"] = time.Now().UTC().Format(time.RFC3339Nano)
	fields["level"] = level
	fields["service"] = l.service
	fields["msg"] = msg
	b, err := json.Marshal(fields)
	if err != nil {
		l.out.Printf(`{"level":"error","msg":"failed to marshal log","error":%q}`, err.Error())
		return
	}
	l.out.Println(string(b))
}
