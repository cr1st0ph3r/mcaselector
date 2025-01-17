package net.querz.mcaselector.debug;

import net.querz.mcaselector.Config;
import java.util.Arrays;

public class Debug {

	public static void dump(Object... objects) {
		if (Config.debug()) {
			for (Object o : objects) {
				System.out.println(o);
			}
		}
	}

	public static void dumpf(String format, Object... objects) {
		if (Config.debug()) {
			System.out.printf(format + "\n", objects);
		}
	}

	public static void error(Object... objects) {
		for (Object o : objects) {
			if (o instanceof Exception) {
				((Exception) o).printStackTrace();
			} else {
				System.out.println(o);
			}
		}
	}

	public static void errorf(String format, Object... objects) {
		System.out.printf(format + "\n", objects);
	}

	public static void print(Object... objects) {
		Arrays.stream(objects).forEach(System.out::println);
	}

	public static void printf(String format, Object... objects) {
		System.out.printf(format + "\n", objects);
	}
}
