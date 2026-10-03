-- usage: osascript bench.applescript <out.json> <turtle probe js> <tester probe js>
on run argv
	set outPath to item 1 of argv
	set probeTurtle to item 2 of argv
	set probeTester to item 3 of argv
	tell application "Safari"
		set oldIds to id of every window
		make new document with properties {URL:"about:blank"}
		delay 1
		set wid to missing value
		set newIds to id of every window
		repeat with x in newIds
			if oldIds does not contain (contents of x) then set wid to (contents of x)
		end repeat
		if wid is missing value then error "no new window"
		set bounds of window id wid to {40, 40, 1480, 1040}
		set t to current tab of window id wid
		-- 1. adblock.turtlecute.org: runs by itself, writes "Total : N" in #test_log when done
		set URL of t to "https://adblock.turtlecute.org/"
		delay 6
		set turtle to "{}"
		repeat 120 times
			try
				set turtle to do JavaScript probeTurtle in t
				if turtle contains "\"done\":true" then exit repeat
			end try
			delay 1
		end repeat
		-- 2. adblock-tester.com: give its checks time to finish
		set URL of t to "https://adblock-tester.com/"
		delay 8
		repeat 40 times
			try
				set tester to do JavaScript probeTester in t
				if tester contains "\"done\":true" then exit repeat
			end try
			delay 1
		end repeat
		delay 3
		set tester to do JavaScript probeTester in t
		close window id wid
	end tell
	set stamp to do shell script "date +%Y-%m-%dT%H:%M:%S"
	set lbl to do shell script "basename " & quoted form of outPath & " .json | sed 's/^bench-//'"
	set json to "{\"label\":\"" & lbl & "\",\"time\":\"" & stamp & "\",\"turtle\":" & turtle & ",\"tester\":" & tester & "}"
	set f to open for access (POSIX file outPath) with write permission
	set eof f to 0
	write json to f as «class utf8»
	close access f
	return outPath
end run
