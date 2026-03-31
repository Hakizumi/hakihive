#pragma once

/**
 * Get user input is y ( Y/Yes ) or n ( N/No )
 * If user input an unmatched choice,this function will retry until user entered a legal choice.
 *
 * @since 1.0.0
 */
bool getInputBoolean();

/**
 * Get user input is y ( Y/Yes ) or n ( N/No )
 * If user input an unmatched choice,this function will return the default boolean value.
 *
 * @since 1.0.0
 */
bool getInputBoolean(bool def);

/**
 * Stuck until input 'enter'
 *
 * @since 1.0.0
 */
void parse();